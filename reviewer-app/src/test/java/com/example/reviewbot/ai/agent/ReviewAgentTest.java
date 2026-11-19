package com.example.reviewbot.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.example.reviewbot.ai.AiUsage;
import com.example.reviewbot.ai.ClaudeClient;
import com.example.reviewbot.ai.ClaudeResponse;
import com.example.reviewbot.ai.PromptLibrary;
import com.example.reviewbot.ai.Purpose;
import com.example.reviewbot.ai.Risk;
import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.ai.TriageVerdict;
import com.example.reviewbot.ai.tools.GetRelatedTestsTool;
import com.example.reviewbot.ai.tools.ReadFileTool;
import com.example.reviewbot.ai.tools.RepoAccess;
import com.example.reviewbot.ai.tools.ReportFindingTool;
import com.example.reviewbot.ai.tools.SearchCodeTool;
import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import com.example.reviewbot.review.PullRequestContext;
import com.example.reviewbot.review.RepoConfig;
import com.example.reviewbot.review.SecretRedactor;
import com.example.reviewbot.support.ClaudeFixtures;
import com.example.reviewbot.support.TestProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewAgentTest {

    private static final String MODEL = "claude-sonnet-5-5";
    private static final String PATH = "src/Cart.java";

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private ReviewAgent agent;
    private FileDiff diff;

    @BeforeEach
    void setUp() {
        agent = new ReviewAgent(claude, new PromptLibrary(), TestProperties.ai(), new ReadFileTool(), new SearchCodeTool(),
                new GetRelatedTestsTool(), new ReportFindingTool());
        diff = new DiffParser().parse(PATH, String.join("\n",
                "@@ -1,3 +1,4 @@",
                " class Cart {",
                "+  int total(List<Item> items) { return items.get(0).price(); }",
                "   void clear() {}",
                " }"));
        when(claude.newRequest(any(), anyLong())).thenAnswer(invocation ->
                MessageCreateParams.builder().model(MODEL).maxTokens(1000));
    }

    @Test
    void collectsReportedFindingsAndStopsWhenTheModelIsDone() {
        when(claude.send(any(), any(), any())).thenReturn(
                response(ClaudeFixtures.toolUseJson(MODEL, ClaudeFixtures.toolUse("t1", "report_finding", Map.of(
                        "file", PATH, "line", 2, "severity", "major", "category", "bug",
                        "title", "Empty cart throws", "message", "`items.get(0)` fails for an empty list.")))),
                response(ClaudeFixtures.textJson(MODEL, "Checked the total calculation.")));

        AgentResult result = agent.review(task(), session());

        assertThat(result.outcome()).isEqualTo(AgentResult.Outcome.COMPLETED);
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.MAJOR);
            assertThat(finding.line()).isEqualTo(2);
            assertThat(finding.lineContent()).contains("items.get(0)");
            assertThat(finding.source()).isEqualTo(ReviewFinding.Source.AGENT);
        });

        ArgumentCaptor<MessageCreateParams> requests = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(claude, times(2)).send(any(), requests.capture(), any());
        MessageCreateParams second = requests.getAllValues().get(1);
        List<BetaMessageParam> history = second.messages();
        assertThat(history).hasSize(3);
        assertThat(ClaudeFixtures.toJson(history.get(1))).contains("\"tool_use\"").contains("report_finding");
        assertThat(ClaudeFixtures.toJson(history.get(2))).contains("\"tool_result\"").contains("Recorded finding 1");
        assertThat(second.tools()).hasValueSatisfying(tools -> assertThat(tools).hasSize(4));
        assertThat(ClaudeFixtures.toJson(second.system().orElseThrow())).contains("cache_control");
        assertThat(second.cacheControl()).isPresent();
    }

    @Test
    void letsTheModelCorrectALineOutsideTheDiff() {
        when(claude.send(any(), any(), any())).thenReturn(
                response(ClaudeFixtures.toolUseJson(MODEL, ClaudeFixtures.toolUse("t1", "report_finding", Map.of(
                        "file", PATH, "line", 40, "severity", "minor", "category", "bug",
                        "title", "x", "message", "y")))),
                response(ClaudeFixtures.textJson(MODEL, "Done.")));

        AgentResult result = agent.review(task(), session());

        assertThat(result.findings()).isEmpty();
        ArgumentCaptor<MessageCreateParams> requests = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(claude, times(2)).send(any(), requests.capture(), any());
        assertThat(ClaudeFixtures.toJson(requests.getAllValues().get(1).messages().get(2)))
                .contains("\"is_error\":true").contains("Line 40 is not part of the diff");
    }

    @Test
    void stopsOnARefusal() {
        when(claude.send(any(), any(), any())).thenReturn(response(ClaudeFixtures.refusalJson(MODEL)));

        AgentResult result = agent.review(task(), session());

        assertThat(result.outcome()).isEqualTo(AgentResult.Outcome.REFUSED);
        assertThat(result.note()).contains("cyber");
    }

    @Test
    void stopsAfterMaxSteps() {
        when(claude.send(any(), any(), any())).thenAnswer(invocation -> response(ClaudeFixtures.toolUseJson(MODEL,
                ClaudeFixtures.toolUse("t1", "search_code", Map.of("query", "total")))));

        AgentResult result = agent.review(task(), session());

        assertThat(result.outcome()).isEqualTo(AgentResult.Outcome.MAX_STEPS);
        assertThat(result.steps()).isEqualTo(TestProperties.ai().agent().maxSteps());
    }

    private AgentTask task() {
        PullRequestContext pr = new PullRequestContext("acme/shop", 3, "Add cart total", "", "bob", "h".repeat(40), "b".repeat(40));
        return new AgentTask(pr, diff, diff, 1, 1, new TriageVerdict(PATH, true, Risk.MEDIUM, "logic change"),
                RepoConfig.defaults(Severity.MINOR, 15), List.of(PATH + " (modified, +1 -0)"));
    }

    private ReviewSession session() {
        RepoAccess repo = new RepoAccess() {
            @Override
            public Optional<String> readFile(String path) {
                return Optional.empty();
            }

            @Override
            public List<String> listFiles() {
                return List.of(PATH);
            }

            @Override
            public List<SearchHit> searchCode(String query, int limit) {
                return List.of();
            }
        };
        return new ReviewSession(repo, TokenBudget.unlimited(), Set.of(PATH), new SecretRedactor(), TestProperties.ai().agent());
    }

    private static ClaudeResponse response(String json) {
        return new ClaudeResponse(ClaudeFixtures.parseMessage(json),
                new AiUsage(MODEL, Purpose.REVIEW, 100, 50, 0, 0, new BigDecimal("0.001"), 10));
    }
}
