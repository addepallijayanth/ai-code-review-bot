package com.example.reviewbot.github;

import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.github.dto.ChangedFile;
import com.example.reviewbot.github.dto.CodeSearchHit;
import com.example.reviewbot.github.dto.Comparison;
import com.example.reviewbot.github.dto.CreateReviewRequest;
import com.example.reviewbot.github.dto.PullRequestInfo;
import com.example.reviewbot.github.dto.ReviewCommentInfo;
import com.example.reviewbot.github.dto.ReviewInfo;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The GitHub REST calls the reviewer makes, each authenticated with the installation token of the
 * repository's installation. Idempotent reads are retried on 5xx and on short rate-limit waits;
 * writes are retried only when GitHub says it did not process them (rate limits).
 */
@Component
public class GitHubClient {

    /** GitHub returns at most 3000 files per pull request; we never need more than this. */
    public static final int MAX_FILES_LISTED = 300;

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofSeconds(30);
    private static final String RAW = "application/vnd.github.raw+json";
    private static final String TEXT_MATCH = "application/vnd.github.text-match+json";

    private enum RetryPolicy { IDEMPOTENT, WRITE, FAIL_FAST }

    private final RestClient http;
    private final GitHubAppAuthenticator authenticator;
    private final URI apiUrl;

    public GitHubClient(GitHubAppProperties properties, GitHubAppAuthenticator authenticator) {
        this.apiUrl = GitHubHttp.stripTrailingSlash(properties.apiUrl());
        this.http = GitHubHttp.create(properties.apiUrl(), Duration.ofSeconds(30));
        this.authenticator = authenticator;
    }

    public PullRequestInfo getPullRequest(long installationId, RepoRef repo, int number) {
        URI uri = uri(Map.of(), "repos", repo.owner(), repo.name(), "pulls", String.valueOf(number));
        return call(installationId, RetryPolicy.IDEMPOTENT,
                () -> http.get().uri(uri).headers(auth(installationId)).retrieve().body(PullRequestInfo.class));
    }

    public List<ChangedFile> listPullRequestFiles(long installationId, RepoRef repo, int number) {
        List<ChangedFile> files = new ArrayList<>();
        for (int page = 1; files.size() < MAX_FILES_LISTED; page++) {
            URI uri = uri(Map.of("per_page", "100", "page", String.valueOf(page)),
                    "repos", repo.owner(), repo.name(), "pulls", String.valueOf(number), "files");
            ChangedFile[] batch = call(installationId, RetryPolicy.IDEMPOTENT,
                    () -> http.get().uri(uri).headers(auth(installationId)).retrieve().body(ChangedFile[].class));
            if (batch == null || batch.length == 0) {
                break;
            }
            files.addAll(Arrays.asList(batch));
            if (batch.length < 100) {
                break;
            }
        }
        return files.size() > MAX_FILES_LISTED ? List.copyOf(files.subList(0, MAX_FILES_LISTED)) : files;
    }

    /** Raw file contents at a commit, or empty when the file does not exist there. */
    public Optional<String> getFileContent(long installationId, RepoRef repo, String path, String ref) {
        List<String> segments = new ArrayList<>(List.of("repos", repo.owner(), repo.name(), "contents"));
        segments.addAll(Arrays.asList(path.split("/")));
        URI uri = uri(Map.of("ref", ref), segments.toArray(String[]::new));
        try {
            byte[] body = call(installationId, RetryPolicy.IDEMPOTENT, () -> http.get().uri(uri)
                    .headers(auth(installationId))
                    .header(HttpHeaders.ACCEPT, RAW)
                    .retrieve()
                    .body(byte[].class));
            return Optional.of(body == null ? "" : new String(body, StandardCharsets.UTF_8));
        } catch (GitHubApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Every file path in the repository at a commit. */
    public List<String> listFilePaths(long installationId, RepoRef repo, String sha) {
        URI uri = uri(Map.of("recursive", "1"), "repos", repo.owner(), repo.name(), "git", "trees", sha);
        TreeResponse tree = call(installationId, RetryPolicy.IDEMPOTENT,
                () -> http.get().uri(uri).headers(auth(installationId)).retrieve().body(TreeResponse.class));
        if (tree == null || tree.tree() == null) {
            return List.of();
        }
        if (tree.truncated()) {
            log.info("File tree of {} at {} is truncated by GitHub; related-test lookup may be incomplete", repo, sha);
        }
        return tree.tree().stream().filter(entry -> "blob".equals(entry.type())).map(TreeEntry::path).toList();
    }

    /**
     * GitHub code search. It indexes the default branch only and allows 10 requests per minute,
     * so this never waits on a rate limit; callers treat failures as "search unavailable".
     */
    public List<CodeSearchHit> searchCode(long installationId, RepoRef repo, String query, int limit) {
        String q = query + " repo:" + repo.fullName();
        URI uri = uri(Map.of("q", q, "per_page", String.valueOf(limit)), "search", "code");
        SearchResponse response = call(installationId, RetryPolicy.FAIL_FAST, () -> http.get().uri(uri)
                .headers(auth(installationId))
                .header(HttpHeaders.ACCEPT, TEXT_MATCH)
                .retrieve()
                .body(SearchResponse.class));
        if (response == null || response.items() == null) {
            return List.of();
        }
        return response.items().stream()
                .map(item -> new CodeSearchHit(item.path(), item.textMatches() == null ? List.of()
                        : item.textMatches().stream().map(TextMatch::fragment).toList()))
                .toList();
    }

    public Comparison compare(long installationId, RepoRef repo, String base, String head) {
        URI uri = uri(Map.of("per_page", "1"), "repos", repo.owner(), repo.name(), "compare", base + "..." + head);
        CompareResponse response = call(installationId, RetryPolicy.IDEMPOTENT,
                () -> http.get().uri(uri).headers(auth(installationId)).retrieve().body(CompareResponse.class));
        List<String> files = response == null || response.files() == null ? List.of()
                : response.files().stream().map(CompareFile::filename).toList();
        String status = response == null ? "unknown" : response.status();
        return new Comparison(status, files, files.size() < 300);
    }

    public long createReview(long installationId, RepoRef repo, int number, CreateReviewRequest request) {
        URI uri = uri(Map.of(), "repos", repo.owner(), repo.name(), "pulls", String.valueOf(number), "reviews");
        ReviewInfo created = call(installationId, RetryPolicy.WRITE, () -> http.post().uri(uri)
                .headers(auth(installationId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ReviewInfo.class));
        if (created == null) {
            throw new GitHubApiException(0, "GitHub returned an empty body for the created review", false, null, null);
        }
        return created.id();
    }

    /** Finds a review whose body contains {@code marker}; used to make a retried post idempotent. */
    public Optional<ReviewInfo> findReviewContaining(long installationId, RepoRef repo, int number, String marker) {
        URI uri = uri(Map.of("per_page", "100"), "repos", repo.owner(), repo.name(), "pulls", String.valueOf(number), "reviews");
        ReviewInfo[] reviews = call(installationId, RetryPolicy.IDEMPOTENT,
                () -> http.get().uri(uri).headers(auth(installationId)).retrieve().body(ReviewInfo[].class));
        if (reviews == null) {
            return Optional.empty();
        }
        return Arrays.stream(reviews).filter(r -> r.body() != null && r.body().contains(marker)).findFirst();
    }

    public ReviewCommentInfo getReviewComment(long installationId, RepoRef repo, long commentId) {
        URI uri = uri(Map.of(), "repos", repo.owner(), repo.name(), "pulls", "comments", String.valueOf(commentId));
        return call(installationId, RetryPolicy.IDEMPOTENT,
                () -> http.get().uri(uri).headers(auth(installationId)).retrieve().body(ReviewCommentInfo.class));
    }

    public void replyToReviewComment(long installationId, RepoRef repo, int number, long commentId, String body) {
        URI uri = uri(Map.of(), "repos", repo.owner(), repo.name(), "pulls", String.valueOf(number),
                "comments", String.valueOf(commentId), "replies");
        call(installationId, RetryPolicy.WRITE, () -> http.post().uri(uri)
                .headers(auth(installationId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity());
    }

    public void createIssueComment(long installationId, RepoRef repo, int number, String body) {
        URI uri = uri(Map.of(), "repos", repo.owner(), repo.name(), "issues", String.valueOf(number), "comments");
        call(installationId, RetryPolicy.WRITE, () -> http.post().uri(uri)
                .headers(auth(installationId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity());
    }

    private Consumer<HttpHeaders> auth(long installationId) {
        return headers -> headers.setBearerAuth(authenticator.installationToken(installationId));
    }

    private URI uri(Map<String, String> query, String... segments) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(apiUrl).pathSegment(segments);
        query.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private <T> T call(long installationId, RetryPolicy policy, Supplier<T> request) {
        boolean tokenRefreshed = false;
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (GitHubApiException e) {
                if (e.status() == 401 && !tokenRefreshed) {
                    authenticator.invalidate(installationId);
                    tokenRefreshed = true;
                    continue;
                }
                if (!shouldRetry(e, policy, attempt)) {
                    throw e;
                }
                Duration wait = e.retryAfter() != null ? e.retryAfter() : backoff(attempt);
                log.debug("GitHub call failed ({}); retrying in {}", e.getMessage(), wait);
                sleep(wait);
            } catch (ResourceAccessException e) {
                if (policy != RetryPolicy.IDEMPOTENT || attempt >= MAX_ATTEMPTS) {
                    throw new GitHubApiException(0, "GitHub request failed: " + e.getMessage(), false, null, e);
                }
                sleep(backoff(attempt));
            }
        }
    }

    private static boolean shouldRetry(GitHubApiException e, RetryPolicy policy, int attempt) {
        if (attempt >= MAX_ATTEMPTS || policy == RetryPolicy.FAIL_FAST) {
            return false;
        }
        if (e.isRateLimited()) {
            return e.retryAfter() == null || e.retryAfter().compareTo(MAX_RATE_LIMIT_WAIT) <= 0;
        }
        return policy == RetryPolicy.IDEMPOTENT && (e.status() >= 500 || e.status() == 429);
    }

    private static Duration backoff(int attempt) {
        long base = 500L * (1L << (attempt - 1));
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(250));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException(0, "Interrupted while waiting to retry a GitHub call", false, null, e);
        }
    }

    record TreeResponse(List<TreeEntry> tree, boolean truncated) {
    }

    record TreeEntry(String path, String type) {
    }

    record SearchResponse(List<SearchItem> items) {
    }

    record SearchItem(String path, List<TextMatch> textMatches) {
    }

    record TextMatch(String fragment) {
    }

    record CompareResponse(String status, List<CompareFile> files) {
    }

    record CompareFile(String filename) {
    }
}
