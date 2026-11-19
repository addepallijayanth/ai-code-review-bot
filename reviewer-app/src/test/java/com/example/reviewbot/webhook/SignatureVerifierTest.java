package com.example.reviewbot.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.config.GitHubAppProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    // Example from GitHub's "Validating webhook deliveries" documentation.
    private static final String DOC_SECRET = "It's a Secret to Everybody";
    private static final String DOC_PAYLOAD = "Hello, World!";
    private static final String DOC_SIGNATURE = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private final SignatureVerifier verifier = new SignatureVerifier(properties(DOC_SECRET));

    @Test
    void acceptsTheSignatureFromGitHubsDocumentation() {
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), DOC_SIGNATURE)).isTrue();
        assertThat(verifier.sign(bytes(DOC_PAYLOAD))).isEqualTo(DOC_SIGNATURE);
    }

    @Test
    void acceptsUppercaseHex() {
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), "sha256=" + DOC_SIGNATURE.substring(7).toUpperCase())).isTrue();
    }

    @Test
    void rejectsATamperedPayload() {
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD + " "), DOC_SIGNATURE)).isFalse();
    }

    @Test
    void rejectsASignatureMadeWithAnotherSecret() {
        SignatureVerifier other = new SignatureVerifier(properties("another secret"));
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), other.sign(bytes(DOC_PAYLOAD)))).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), null)).isFalse();
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), "")).isFalse();
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), DOC_SIGNATURE.substring(7))).isFalse();
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), "sha1=" + DOC_SIGNATURE.substring(7))).isFalse();
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), "sha256=not-hex")).isFalse();
        assertThat(verifier.isValid(bytes(DOC_PAYLOAD), "sha256=abcd")).isFalse();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static GitHubAppProperties properties(String secret) {
        return new GitHubAppProperties("1", "reviewbot", null, null, secret, URI.create("https://api.github.com"),
                "https://github.com", "@reviewbot");
    }
}
