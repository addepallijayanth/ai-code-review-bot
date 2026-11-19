package com.example.reviewbot.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.support.TestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GitHubAppAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void readsPkcs8AndPkcs1Keys() {
        PrivateKey pkcs8 = PrivateKeyLoader.parse(TestKeys.pkcs8Pem());
        PrivateKey pkcs1 = PrivateKeyLoader.parse(TestKeys.pkcs1Pem());

        assertThat(pkcs8.getEncoded()).isEqualTo(TestKeys.keyPair().getPrivate().getEncoded());
        assertThat(pkcs1.getEncoded()).isEqualTo(TestKeys.keyPair().getPrivate().getEncoded());
    }

    @Test
    void readsAKeyWhoseNewlinesWereEscapedForAnEnvironmentVariable() {
        String escaped = TestKeys.pkcs1Pem().replace("\n", "\\n");
        assertThat(PrivateKeyLoader.parse(escaped).getEncoded()).isEqualTo(TestKeys.keyPair().getPrivate().getEncoded());
    }

    @Test
    void rejectsSomethingThatIsNotAKey() {
        assertThatThrownBy(() -> PrivateKeyLoader.parse("-----BEGIN RSA PRIVATE KEY-----\nbm9wZQ==\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PrivateKeyLoader.load(null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signsAnRs256JwtThatGitHubAccepts() throws Exception {
        GitHubAppAuthenticator authenticator = new GitHubAppAuthenticator(properties(), Clock.fixed(NOW, ZoneOffset.UTC));

        String jwt = authenticator.createAppJwt();
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode header = GitHubJson.mapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = GitHubJson.mapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(claims.path("iss").asLong()).isEqualTo(123456L);
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond() - 60);
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 540);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(TestKeys.keyPair().getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private static GitHubAppProperties properties() {
        return new GitHubAppProperties("123456", "reviewbot", TestKeys.pkcs1Pem(), null, "secret",
                URI.create("http://localhost:1"), "https://github.com", "@reviewbot");
    }
}
