package com.example.reviewbot.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.reviewbot.ReviewBotApplication;
import com.example.reviewbot.installation.InstallationService;
import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.Finding;
import com.example.reviewbot.model.Review;
import com.example.reviewbot.model.ReviewStatus;
import com.example.reviewbot.model.Severity;
import com.example.reviewbot.model.UsageRecord;
import com.example.reviewbot.queue.JobQueue;
import com.example.reviewbot.repository.FindingRepository;
import com.example.reviewbot.repository.ReviewRepository;
import com.example.reviewbot.repository.UsageRecordRepository;
import com.example.reviewbot.support.ClaudeFixtures;
import com.example.reviewbot.support.InMemoryQueueConfig;
import com.example.reviewbot.support.TestInfrastructure;
import com.example.reviewbot.support.TestKeys;
import com.example.reviewbot.webhook.SignatureVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole flow against real PostgreSQL and Redis, with GitHub and the Claude API stubbed by
 * WireMock: signed webhook → queue → worker → triage → agent tool loop → review posted on GitHub,
 * plus idempotency, signature rejection, and a chat command in a finding's thread.
 */
@SpringBootTest(classes = ReviewBotApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InMemoryQueueConfig.class)
class WebhookFlowIT {

    private static final String SECRET = "it-webhook-secret";
    private static final long INSTALLATION_ID = 4242;
    private static final String REPO = "acme/shop";
    private static final String FILE = "src/main/java/com/acme/UserDao.java";
    private static final String HAIKU = "claude-haiku-5-5";
    private static final String SONNET = "claude-sonnet-5-5";

    private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());
    private static final WireMockServer CLAUDE = new WireMockServer(options().dynamicPort());

    static {
        GITHUB.start();
        CLAUDE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestInfrastructure.get().register(registry);
        registry.add("reviewbot.github.app-id", () -> "123456");
        registry.add("reviewbot.github.app-slug", () -> "reviewbot-it");
        registry.add("reviewbot.github.private-key", TestKeys::pkcs1Pem);
        registry.add("reviewbot.github.webhook-secret", () -> SECRET);
        registry.add("reviewbot.github.api-url", GITHUB::baseUrl);
        registry.add("reviewbot.ai.api-key", () -> "test-key");
        registry.add("reviewbot.ai.base-url", CLAUDE::baseUrl);
        registry.add("reviewbot.ai.max-retries", () -> 0);
        registry.add("reviewbot.queue.stream-key", () -> "it:jobs:" + UUID.randomUUID());
        registry.add("reviewbot.queue.poll-timeout", () -> "500ms");
        registry.add("spring.security.user.name", () -> "admin");
        registry.add("spring.security.user.password", () -> DASHBOARD_PASSWORD);
    }

    private static final String DASHBOARD_PASSWORD = "it-dashboard-password";

    @AfterAll
    static void stopServers() {
        GITHUB.stop();
        CLAUDE.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    SignatureVerifier signatures;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    FindingRepository findings;

    @Autowired
    UsageRecordRepository usage;

    @Autowired
    InstallationService installations;

    @Autowired
    JobQueue queue;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetStubs() {
        GITHUB.resetAll();
        CLAUDE.resetAll();
        GITHUB.stubFor(post(urlPathEqualTo("/app/installations/" + INSTALLATION_ID + "/access_tokens"))
                .willReturn(okJson("{\"token\":\"ghs_it\",\"expires_at\":\"2099-01-01T00:00:00Z\"}")));
    }

    @Test
    void reviewsAPullRequestOnceAndPostsInlineFindings() throws Exception {
        int number = 7;
        String head = "a1".repeat(20);
        String base = "b2".repeat(20);
        stubPullRequest(number, head, base);
        GITHUB.stubFor(get(urlPathEqualTo("/repos/acme/shop/contents/.reviewbot.yml"))
                .willReturn(aResponse().withStatus(200).withBody("min_severity: minor\nfocus: [security]\n")));
        GITHUB.stubFor(get(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/files")).willReturn(okJson(changedFiles())));
        GITHUB.stubFor(get(urlPathEqualTo("/repos/acme/shop/contents/" + FILE)).willReturn(aResponse().withBody(fileContent())));
        GITHUB.stubFor(post(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/reviews"))
                .willReturn(okJson("{\"id\":9001,\"body\":\"\",\"user\":{\"login\":\"reviewbot-it[bot]\",\"type\":\"Bot\"}}")));
        stubClaudeReview();

        HttpResponse<String> response = deliver("pull_request", pullRequestPayload("opened", number, head, base));
        assertThat(response.statusCode()).isEqualTo(202);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                GITHUB.verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/reviews"))));

        JsonNode posted = json.readTree(postedReview(number).getBodyAsString());
        assertThat(posted.path("commit_id").asText()).isEqualTo(head);
        assertThat(posted.path("event").asText()).isEqualTo("COMMENT");
        assertThat(posted.path("body").asText())
                .contains("Reviewed 1 file")
                .contains("1 critical")
                .contains("package-lock.json")
                .contains("<!-- reviewbot:review:");
        JsonNode comment = posted.path("comments").get(0);
        assertThat(posted.path("comments")).hasSize(1);
        assertThat(comment.path("path").asText()).isEqualTo(FILE);
        assertThat(comment.path("start_line").asInt()).isEqualTo(13);
        assertThat(comment.path("line").asInt()).isEqualTo(15);
        assertThat(comment.path("side").asText()).isEqualTo("RIGHT");
        assertThat(comment.path("body").asText())
                .contains("Critical")
                .contains("```suggestion")
                .contains("prepareStatement");

        Review review = await().atMost(Duration.ofSeconds(10)).until(
                () -> reviews.findByRepoAndPrNumberAndCommitSha(REPO, number, head).orElse(null),
                found -> found != null && found.getStatus() == ReviewStatus.COMPLETED);
        assertThat(review.getGithubReviewId()).isEqualTo(9001L);
        assertThat(review.getFilesReviewed()).isEqualTo(1);
        List<Finding> stored = findings.findByReviewIdOrderByOrdinal(review.getId());
        assertThat(stored).singleElement().satisfies(finding -> {
            assertThat(finding.getSeverity()).isEqualTo(Severity.CRITICAL);
            assertThat(finding.isPostedInline()).isTrue();
            assertThat(finding.getSuggestion()).contains("prepareStatement");
        });
        List<UsageRecord> records = usage.findAll().stream().filter(u -> u.getReviewId().equals(review.getId())).toList();
        assertThat(records).extracting(UsageRecord::getPurpose).contains("TRIAGE", "REVIEW");
        assertThat(records).allSatisfy(record -> assertThat(record.getCostUsd()).isPositive());

        // A redelivery of the same commit must not produce a second review.
        assertThat(deliver("pull_request", pullRequestPayload("opened", number, head, base)).statusCode()).isEqualTo(202);
        awaitQueueDrained();
        GITHUB.verify(1, postRequestedFor(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/reviews")));
    }

    @Test
    void rejectsDeliveriesWithAnInvalidSignature() throws Exception {
        byte[] body = pullRequestPayload("opened", 9, "c3".repeat(20), "d4".repeat(20)).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/webhook"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .header("X-Hub-Signature-256", "sha256=" + "00".repeat(32))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void dashboardRequiresLoginAndRendersTheStats() throws Exception {
        URI dashboard = URI.create("http://localhost:" + port + "/dashboard");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(dashboard).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        String credentials = java.util.Base64.getEncoder()
                .encodeToString(("admin:" + DASHBOARD_PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(dashboard)
                .header("Authorization", "Basic " + credentials).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Pull requests reviewed", "Findings by severity", "Prompt cache hit rate", "Critical");

        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/liveness"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }

    @Test
    void ignoresDraftPullRequests() throws Exception {
        String payload = pullRequestPayload("opened", 10, "e5".repeat(20), "f6".repeat(20)).replace("\"draft\":false", "\"draft\":true");
        HttpResponse<String> response = deliver("pull_request", payload);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ignored");
    }

    @Test
    void answersExplainInAFindingThread() throws Exception {
        int number = 8;
        String head = "c3".repeat(20);
        long installationRow = installations.ensure(INSTALLATION_ID, "acme").getId();
        Review review = new Review(installationRow, REPO, number, head, "delivery-seed", Instant.now(), Instant.now());
        review.complete(1, 0, 77L, Instant.now());
        review = reviews.saveAndFlush(review);
        findings.saveAndFlush(new Finding(review.getId(), 1, FILE, 13, null, Severity.CRITICAL, Category.SECURITY,
                "Query built from request parameter", "The name is concatenated into SQL.", null,
                "f".repeat(64), true, Instant.now()));

        GITHUB.stubFor(get(urlPathEqualTo("/repos/acme/shop/pulls/comments/555")).willReturn(okJson("""
                {"id":555,"user":{"login":"reviewbot-it[bot]","type":"Bot"},"path":"%s","line":13,
                 "body":"🔴 **Critical** <!-- reviewbot:finding:%d:1 -->"}
                """.formatted(FILE, review.getId()))));
        GITHUB.stubFor(get(urlPathEqualTo("/repos/acme/shop/contents/" + FILE)).willReturn(aResponse().withBody(fileContent())));
        GITHUB.stubFor(post(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/comments/555/replies"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":556}")));
        CLAUDE.stubFor(post(urlPathEqualTo("/v1/messages"))
                .withRequestBody(containing("replying on GitHub to a developer"))
                .willReturn(okJson(ClaudeFixtures.textJson(SONNET,
                        "The value of `name` ends up inside the SQL text, so `x' OR '1'='1` returns every user."))));

        String payload = """
                {"action":"created",
                 "comment":{"id":556,"body":"@reviewbot explain why is this exploitable?","user":{"login":"alice","type":"User"},
                            "in_reply_to_id":555,"path":"%s","line":13},
                 "pull_request":{"number":%d},
                 "repository":{"id":1,"name":"shop","full_name":"acme/shop","owner":{"login":"acme","type":"Organization"}},
                 "installation":{"id":%d}}
                """.formatted(FILE, number, INSTALLATION_ID);
        assertThat(deliver("pull_request_review_comment", payload).statusCode()).isEqualTo(202);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> GITHUB.verify(1,
                postRequestedFor(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/comments/555/replies"))
                        .withRequestBody(containing("returns every user"))));
        long reviewId = review.getId();
        await().atMost(Duration.ofSeconds(10)).until(() -> usage.findAll().stream()
                .anyMatch(u -> u.getReviewId().equals(reviewId) && u.getPurpose().equals("EXPLAIN")));
    }

    private void stubPullRequest(int number, String head, String base) {
        GITHUB.stubFor(get(urlPathEqualTo("/repos/acme/shop/pulls/" + number)).willReturn(okJson("""
                {"number":%d,"state":"open","draft":false,"title":"Add user lookup by name",
                 "body":"Adds UserDao.findByName for the admin search page.",
                 "user":{"login":"alice","type":"User"},
                 "head":{"sha":"%s","ref":"feature/user-lookup"},"base":{"sha":"%s","ref":"main"}}
                """.formatted(number, head, base))));
    }

    private void stubClaudeReview() throws Exception {
        String verdicts = json.writeValueAsString(Map.of("files", List.of(Map.of(
                "path", FILE, "needsDeepReview", true, "risk", "high", "reason", "Builds a SQL query"))));
        CLAUDE.stubFor(post(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.model", equalTo(HAIKU)))
                .willReturn(okJson(ClaudeFixtures.textJson(HAIKU, verdicts))));

        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("file", FILE);
        finding.put("start_line", 13);
        finding.put("line", 15);
        finding.put("severity", "critical");
        finding.put("category", "security");
        finding.put("title", "Query built from request parameter allows SQL injection");
        finding.put("message", "`name` is concatenated into the SQL string. Use a PreparedStatement with a bound parameter.");
        finding.put("suggestion", """
                        String sql = "SELECT id, name FROM users WHERE name = ?";
                        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
                            s.setString(1, name);
                            return map(s.executeQuery());""".stripTrailing());
        // Second turn (the request carries tool results): the agent is done.
        CLAUDE.stubFor(post(urlPathEqualTo("/v1/messages")).atPriority(1)
                .withRequestBody(matchingJsonPath("$.model", equalTo(SONNET)))
                .withRequestBody(containing("tool_result"))
                .willReturn(okJson(ClaudeFixtures.textJson(SONNET, "Checked how the query is built and executed."))));
        // First turn: read the file, then report the finding.
        CLAUDE.stubFor(post(urlPathEqualTo("/v1/messages")).atPriority(5)
                .withRequestBody(matchingJsonPath("$.model", equalTo(SONNET)))
                .willReturn(okJson(ClaudeFixtures.toolUseJson(SONNET,
                        ClaudeFixtures.toolUse("toolu_1", "read_file", Map.of("path", FILE)),
                        ClaudeFixtures.toolUse("toolu_2", "report_finding", finding)))));
    }

    private LoggedRequest postedReview(int number) {
        return GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/acme/shop/pulls/" + number + "/reviews"))).get(0);
    }

    private void awaitQueueDrained() {
        await().atMost(Duration.ofSeconds(30)).until(() -> queue.backlog() == 0);
    }

    private HttpResponse<String> deliver(String event, String payload) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/webhook"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                .header("X-Hub-Signature-256", signatures.sign(body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String pullRequestPayload(String action, int number, String head, String base) {
        return """
                {"action":"%s","number":%d,
                 "pull_request":{"number":%d,"state":"open","draft":false,"title":"Add user lookup by name","body":"",
                                 "user":{"login":"alice","type":"User"},
                                 "head":{"sha":"%s","ref":"feature/user-lookup"},"base":{"sha":"%s","ref":"main"}},
                 "repository":{"id":1,"name":"shop","full_name":"acme/shop","owner":{"login":"acme","type":"Organization"},
                               "default_branch":"main"},
                 "installation":{"id":%d},
                 "sender":{"login":"alice","type":"User"}}
                """.formatted(action, number, number, head, base, INSTALLATION_ID);
    }

    private String changedFiles() throws Exception {
        String patch = String.join("\n",
                "@@ -8,4 +8,11 @@ public class UserDao {",
                "     private final DataSource dataSource;",
                " ",
                "     public UserDao(DataSource dataSource) { this.dataSource = dataSource; }",
                "+",
                "+    public User findByName(String name) throws SQLException {",
                "+        String sql = \"SELECT id, name FROM users WHERE name = '\" + name + \"'\";",
                "+        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {",
                "+            return map(s.executeQuery(sql));",
                "+        }",
                "+    }",
                " }");
        return json.writeValueAsString(List.of(
                Map.of("filename", FILE, "status", "modified", "additions", 7, "deletions", 0, "changes", 7, "patch", patch),
                Map.of("filename", "package-lock.json", "status", "modified", "additions", 40, "deletions", 12,
                        "changes", 52, "patch", "@@ -1,1 +1,1 @@\n-a\n+b")));
    }

    private static String fileContent() {
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 7; line++) {
            content.append("// header line ").append(line).append('\n');
        }
        return content.append("""
                    private final DataSource dataSource;

                    public UserDao(DataSource dataSource) { this.dataSource = dataSource; }

                    public User findByName(String name) throws SQLException {
                        String sql = "SELECT id, name FROM users WHERE name = '" + name + "'";
                        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                            return map(s.executeQuery(sql));
                        }
                    }
                }
                """).toString();
    }
}
