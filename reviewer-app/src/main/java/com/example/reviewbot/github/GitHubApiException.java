package com.example.reviewbot.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/** A GitHub API call that failed. {@code status} is 0 when no HTTP response was received. */
public class GitHubApiException extends RuntimeException {

    private static final int MAX_BODY_BYTES = 4096;

    private final int status;
    private final boolean rateLimited;
    private final Duration retryAfter;

    public GitHubApiException(int status, String message, boolean rateLimited, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.rateLimited = rateLimited;
        this.retryAfter = retryAfter;
    }

    static GitHubApiException from(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        HttpHeaders headers = response.getHeaders();
        String body;
        try (InputStream in = response.getBody()) {
            body = new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        }
        Duration retryAfter = retryAfter(headers);
        boolean rateLimited = (status == 403 || status == 429)
                && (retryAfter != null || "0".equals(headers.getFirst("x-ratelimit-remaining")));
        String message = request.getMethod() + " " + request.getURI().getPath() + " returned " + status + ": "
                + describe(body);
        return new GitHubApiException(status, message, rateLimited, retryAfter, null);
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
            } catch (NumberFormatException ignored) {
                // HTTP-date form; fall through to the rate-limit reset header
            }
        }
        String reset = headers.getFirst("x-ratelimit-reset");
        if (reset != null && "0".equals(headers.getFirst("x-ratelimit-remaining"))) {
            try {
                Duration untilReset = Duration.between(Instant.now(), Instant.ofEpochSecond(Long.parseLong(reset)));
                return untilReset.isNegative() ? Duration.ZERO : untilReset;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String describe(String body) {
        try {
            JsonNode json = GitHubJson.mapper().readTree(body);
            String message = json.path("message").asText("");
            JsonNode errors = json.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                return message + " " + errors;
            }
            if (!message.isEmpty()) {
                return message;
            }
        } catch (IOException ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    public int status() {
        return status;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /** Whether trying the whole job again later could succeed. */
    public boolean isRetryable() {
        return status == 0 || status == 429 || status >= 500 || rateLimited;
    }
}
