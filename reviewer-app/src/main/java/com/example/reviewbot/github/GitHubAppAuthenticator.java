package com.example.reviewbot.github;

import com.example.reviewbot.config.GitHubAppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Authenticates as the GitHub App: signs a short-lived RS256 JWT with the App's private key and
 * exchanges it for an installation access token, which is scoped to the repositories of that one
 * installation. Tokens live for an hour and are cached until five minutes before they expire.
 */
@Component
public class GitHubAppAuthenticator {

    private static final Duration JWT_BACKDATE = Duration.ofSeconds(60);
    private static final Duration JWT_LIFETIME = Duration.ofMinutes(9);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final String appId;
    private final PrivateKey privateKey;
    private final Clock clock;
    private final RestClient http;
    private final Map<Long, CachedToken> tokens = new ConcurrentHashMap<>();

    public GitHubAppAuthenticator(GitHubAppProperties properties, Clock clock) {
        this.appId = properties.appId();
        this.privateKey = PrivateKeyLoader.load(properties.privateKey(), properties.privateKeyPath());
        this.clock = clock;
        this.http = GitHubHttp.create(properties.apiUrl(), Duration.ofSeconds(20));
    }

    /** JWT that identifies the App itself (not an installation). Valid for about nine minutes. */
    public String createAppJwt() {
        Instant now = clock.instant();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iat", now.minus(JWT_BACKDATE).getEpochSecond());
        claims.put("exp", now.plus(JWT_LIFETIME).getEpochSecond());
        claims.put("iss", issuer());

        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign GitHub App JWT", e);
        }
    }

    public String installationToken(long installationId) {
        CachedToken cached = tokens.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(clock.instant().plus(REFRESH_MARGIN))) {
            return cached.token();
        }
        AccessTokenResponse response = http.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAppJwt())
                .retrieve()
                .body(AccessTokenResponse.class);
        if (response == null || response.token() == null) {
            throw new GitHubApiException(0, "GitHub returned no installation token for " + installationId, false, null, null);
        }
        Instant expiresAt = response.expiresAt() != null ? response.expiresAt() : clock.instant().plus(Duration.ofMinutes(55));
        tokens.put(installationId, new CachedToken(response.token(), expiresAt));
        return response.token();
    }

    /** Drops a cached token, e.g. after GitHub answered 401 because it was revoked. */
    public void invalidate(long installationId) {
        tokens.remove(installationId);
    }

    private Object issuer() {
        // GitHub accepts the numeric App ID or the App's client ID as the issuer.
        try {
            return Long.parseLong(appId.trim());
        } catch (NumberFormatException e) {
            return appId.trim();
        }
    }

    private static String encodeJson(Map<String, Object> value) {
        try {
            return BASE64URL.encodeToString(GitHubJson.mapper().writeValueAsBytes(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    record AccessTokenResponse(String token, Instant expiresAt) {
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
