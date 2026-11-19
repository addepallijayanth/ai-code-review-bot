package com.example.reviewbot.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The GitHub tools the review agent uses, as MCP tools. Results are plain text sized for a model's
 * context: numbered file lines, short search fragments, one line per pull request.
 */
@Service
public class GitHubTools {

    static final int MAX_FILE_LINES = 2000;
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final RestClient http;
    private final URI apiUrl;

    public GitHubTools(GitHubProperties properties) {
        String base = properties.apiUrl().toString();
        this.apiUrl = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "ai-code-review-bot-mcp");
        if (StringUtils.hasText(properties.token())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token().trim());
        }
        this.http = builder.build();
    }

    @Tool(name = "read_file", description = "Read a file from a GitHub repository, with line numbers. "
            + "Use it to see code around a change or to follow a call into another file.")
    public String readFile(
            @ToolParam(description = "Repository as owner/name, e.g. octocat/hello-world") String repository,
            @ToolParam(description = "Path inside the repository, e.g. src/main/java/App.java") String path,
            @ToolParam(description = "Branch, tag or commit SHA; the default branch when omitted", required = false) String ref) {
        String repo = requireRepository(repository);
        List<String> segments = new ArrayList<>(List.of("repos", owner(repo), name(repo), "contents"));
        segments.addAll(Arrays.stream(path.strip().replaceFirst("^/+", "").split("/")).filter(s -> !s.isEmpty()).toList());
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(apiUrl).pathSegment(segments.toArray(String[]::new));
        if (StringUtils.hasText(ref)) {
            uri.queryParam("ref", ref.strip());
        }
        try {
            byte[] body = http.get().uri(uri.build().encode().toUri())
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json")
                    .retrieve()
                    .body(byte[].class);
            String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) {
                return path + " is a binary file.";
            }
            return numbered(text);
        } catch (HttpClientErrorException.NotFound e) {
            return "No file at " + path + " in " + repo + (StringUtils.hasText(ref) ? " at " + ref : "") + ".";
        } catch (RestClientResponseException e) {
            return failure(e);
        }
    }

    @Tool(name = "search_code", description = "Search a GitHub repository's code for an identifier or short phrase, "
            + "for example a method name to find its callers. GitHub indexes the default branch only.")
    public String searchCode(
            @ToolParam(description = "Repository as owner/name") String repository,
            @ToolParam(description = "Identifier or short exact phrase to search for") String query) {
        String repo = requireRepository(repository);
        URI uri = UriComponentsBuilder.fromUri(apiUrl).pathSegment("search", "code")
                .queryParam("q", query.strip() + " repo:" + repo)
                .queryParam("per_page", "10")
                .build().encode().toUri();
        try {
            SearchResponse response = http.get().uri(uri)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.text-match+json")
                    .retrieve()
                    .body(SearchResponse.class);
            if (response == null || response.items() == null || response.items().isEmpty()) {
                return "No results for \"" + query + "\" in " + repo + ".";
            }
            StringBuilder out = new StringBuilder();
            for (SearchItem item : response.items()) {
                out.append(item.path()).append('\n');
                if (item.textMatches() != null) {
                    for (TextMatch match : item.textMatches()) {
                        out.append("    ").append(match.fragment().strip().replace("\n", "\n    ")).append('\n');
                    }
                }
            }
            return out.toString().stripTrailing();
        } catch (RestClientResponseException e) {
            return failure(e);
        }
    }

    @Tool(name = "list_prs", description = "List pull requests of a GitHub repository, newest first: number, title, "
            + "author, branches and last update.")
    public String listPullRequests(
            @ToolParam(description = "Repository as owner/name") String repository,
            @ToolParam(description = "open, closed or all; open when omitted", required = false) String state) {
        String repo = requireRepository(repository);
        String filter = StringUtils.hasText(state) ? state.strip().toLowerCase() : "open";
        if (!List.of("open", "closed", "all").contains(filter)) {
            return "state must be open, closed or all.";
        }
        URI uri = UriComponentsBuilder.fromUri(apiUrl).pathSegment("repos", owner(repo), name(repo), "pulls")
                .queryParam("state", filter)
                .queryParam("sort", "updated")
                .queryParam("direction", "desc")
                .queryParam("per_page", "30")
                .build().encode().toUri();
        try {
            PullRequest[] pulls = http.get().uri(uri).retrieve().body(PullRequest[].class);
            if (pulls == null || pulls.length == 0) {
                return "No " + (filter.equals("all") ? "" : filter + " ") + "pull requests in " + repo + ".";
            }
            StringBuilder out = new StringBuilder();
            for (PullRequest pr : pulls) {
                out.append('#').append(pr.number()).append(' ').append(pr.title())
                        .append(" (").append(pr.user() == null ? "unknown" : pr.user().get("login"))
                        .append(", ").append(pr.head() == null ? "?" : pr.head().get("ref"))
                        .append(" -> ").append(pr.base() == null ? "?" : pr.base().get("ref"))
                        .append(pr.draft() ? ", draft" : "")
                        .append(", updated ").append(pr.updatedAt()).append(")\n");
            }
            return out.toString().stripTrailing();
        } catch (RestClientResponseException e) {
            return failure(e);
        }
    }

    static String numbered(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int total = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(total, MAX_FILE_LINES); i++) {
            String number = String.valueOf(i + 1);
            out.append(" ".repeat(Math.max(0, 6 - number.length()))).append(number).append("| ").append(lines[i]).append('\n');
        }
        if (total > MAX_FILE_LINES) {
            out.append("... ").append(total - MAX_FILE_LINES).append(" more lines not shown\n");
        }
        return out.toString();
    }

    private static String requireRepository(String repository) {
        String repo = repository == null ? "" : repository.strip();
        if (!REPOSITORY.matcher(repo).matches()) {
            throw new IllegalArgumentException("repository must look like owner/name, got: " + repository);
        }
        return repo;
    }

    private static String owner(String repo) {
        return repo.substring(0, repo.indexOf('/'));
    }

    private static String name(String repo) {
        return repo.substring(repo.indexOf('/') + 1);
    }

    private static String failure(RestClientResponseException e) {
        return "GitHub returned " + e.getStatusCode().value() + ": " + e.getStatusText()
                + (e.getStatusCode().value() == 401 ? " (check GITHUB_TOKEN)" : "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchItem(String path, @JsonProperty("text_matches") List<TextMatch> textMatches) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TextMatch(String fragment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequest(int number, String title, boolean draft, Map<String, Object> user, Map<String, Object> head,
                       Map<String, Object> base, @JsonProperty("updated_at") String updatedAt) {
    }
}
