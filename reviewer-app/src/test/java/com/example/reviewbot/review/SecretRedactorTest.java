package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.FileDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    // Token-shaped test values are assembled at runtime so the source never contains a string
    // that secret scanners (including GitHub push protection) would flag.
    private static final String AWS_KEY = "AKIA" + "IOSFODNN7EXAMPLE";
    private static final String OLD_AWS_KEY = "AKIA" + "OLDOLDOLDOLDOLD1";
    private static final String GITHUB_TOKEN = "ghp" + "_" + "a".repeat(36);
    private static final String ANTHROPIC_KEY = "sk-" + "ant-api03-" + "b".repeat(40);
    private static final String SLACK_TOKEN = "xox" + "b-1234567890-" + "abcdefghij";
    private static final String STRIPE_KEY = "sk" + "_live_" + "c".repeat(24);

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsWellKnownTokenFormats() {
        String text = String.join("\n",
                "aws = \"" + AWS_KEY + "\"",
                "token = '" + GITHUB_TOKEN + "'",
                "ANTHROPIC_API_KEY=" + ANTHROPIC_KEY,
                "slack: " + SLACK_TOKEN,
                "stripe = \"" + STRIPE_KEY + "\"");

        String redacted = redactor.redact(text);

        assertThat(redacted).doesNotContain(AWS_KEY, GITHUB_TOKEN, ANTHROPIC_KEY, SLACK_TOKEN, STRIPE_KEY);
        assertThat(redacted).contains("[REDACTED:aws-access-key]", "[REDACTED:github-token]",
                "[REDACTED:anthropic-api-key]", "[REDACTED:slack-token]", "[REDACTED:stripe-secret-key]");
        assertThat(redacted.lines()).hasSize(5);
    }

    @Test
    void redactsHardcodedPasswordsButKeepsPlaceholders() {
        String redacted = redactor.redact(String.join("\n",
                "String password = \"hunter2-prod!\";",
                "db.password: \"${DB_PASSWORD}\"",
                "password = \"changeme\"",
                "apiKey: \"<your key here>\""));

        List<String> lines = redacted.lines().toList();
        assertThat(lines.get(0)).isEqualTo("String password = \"[REDACTED:credential]\";");
        assertThat(lines.get(1)).contains("${DB_PASSWORD}");
        assertThat(lines.get(2)).contains("changeme");
        assertThat(lines.get(3)).contains("<your key here>");
    }

    @Test
    void redactsAWholePrivateKeyBlockLineByLine() {
        List<String> lines = List.of(
                "const key = `",
                "-----BEGIN RSA " + "PRIVATE KEY-----",
                "MIIEowIBAAKCAQEAuQ0nd1Q2",
                "kTZ8Yq1x9Pf6",
                "-----END RSA " + "PRIVATE KEY-----",
                "`;");

        List<String> redacted = redactor.redactLines(lines);

        assertThat(redacted).hasSize(6);
        assertThat(redacted.get(0)).isEqualTo("const key = `");
        assertThat(redacted.subList(1, 5)).allSatisfy(line -> assertThat(line).isEqualTo("[REDACTED:private-key]"));
        assertThat(redacted.get(5)).isEqualTo("`;");
    }

    @Test
    void redactsAOneLineEscapedPrivateKey() {
        String line = "\"private_key\": \"-----BEGIN " + "PRIVATE KEY-----\\nMIIEvQIBADAN\\n-----END " + "PRIVATE KEY-----\\n\",";

        assertThat(redactor.redact(line)).isEqualTo("\"private_key\": \"[REDACTED:private-key]\\n\",");
    }

    @Test
    void reportsHighConfidenceSecretsOnAddedLinesOnly() {
        FileDiff diff = new DiffParser().parse("config/app.properties", String.join("\n",
                "@@ -1,2 +1,3 @@",
                " app.name=shop",
                "-aws.key=" + OLD_AWS_KEY,
                "+aws.key=" + AWS_KEY,
                "+db.password=\"s3cr3t-value\""));

        List<SecretRedactor.SecretHit> hits = redactor.scan(diff);

        assertThat(hits).containsExactly(new SecretRedactor.SecretHit(2, "AWS access key"));
    }
}
