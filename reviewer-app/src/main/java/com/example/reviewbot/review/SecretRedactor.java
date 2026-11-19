package com.example.reviewbot.review;

import com.example.reviewbot.github.FileDiff;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Removes anything that looks like a key, token or password from code before it is sent to the
 * model, keeping the line structure intact so line numbers stay valid. Redacted values read as
 * {@code [REDACTED:kind]}, which still lets the model point out a hardcoded credential.
 * <p>
 * High-confidence patterns found on added lines are also reported directly as critical findings,
 * since the model never sees the actual value.
 */
@Component
public class SecretRedactor {

    public static final String MARKER_PREFIX = "[REDACTED:";

    /** A secret on an added line of a diff. */
    public record SecretHit(int line, String label) {
    }

    private record Rule(String id, String label, Pattern pattern, int group, boolean reportable) {
    }

    private static final Pattern PRIVATE_KEY_BEGIN = Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----");
    private static final Pattern PRIVATE_KEY_END = Pattern.compile("-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----");

    // Order matters: more specific prefixes (sk-ant-) must come before broader ones (sk-).
    private static final List<Rule> RULES = List.of(
            new Rule("aws-access-key", "AWS access key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), 0, true),
            new Rule("github-token", "GitHub token",
                    Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})\\b"), 0, true),
            new Rule("anthropic-api-key", "Anthropic API key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}"), 0, true),
            new Rule("openai-api-key", "OpenAI API key", Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}"), 0, true),
            new Rule("slack-token", "Slack token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), 0, true),
            new Rule("stripe-secret-key", "Stripe live secret key", Pattern.compile("\\b(?:sk|rk)_live_[A-Za-z0-9]{16,}"), 0, true),
            new Rule("google-api-key", "Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), 0, true),
            new Rule("jwt", "JSON Web Token",
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), 0, false),
            new Rule("credential", "credential", Pattern.compile(
                    "(?i)\\b(?:api[_-]?key|secret(?:[_-]?key)?|client[_-]?secret|access[_-]?token|auth[_-]?token"
                            + "|password|passwd|pwd)\\b[\"']?\\s*[:=]\\s*[\"']([^\"'\\s]{6,})[\"']"), 1, false));

    /** Values that are obviously not real secrets and must stay visible, e.g. {@code ${DB_PASSWORD}}. */
    private static final Set<String> PLACEHOLDERS = Set.of("changeme", "change-me", "password", "secret", "example",
            "placeholder", "dummy", "xxxxxx", "******", "redacted", "your-api-key", "your_api_key", "todo");

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = Arrays.asList(text.split("\n", -1));
        return String.join("\n", redactLines(lines));
    }

    /** Redacts each line; a private-key block is redacted from its BEGIN line through its END line. */
    public List<String> redactLines(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        boolean inPrivateKey = false;
        for (String line : lines) {
            if (line == null) {
                result.add(null);
                continue;
            }
            if (inPrivateKey) {
                result.add(marker("private-key"));
                if (PRIVATE_KEY_END.matcher(line).find()) {
                    inPrivateKey = false;
                }
                continue;
            }
            Matcher begin = PRIVATE_KEY_BEGIN.matcher(line);
            if (begin.find()) {
                // Covers both a multi-line PEM block and a one-line, "\n"-escaped key.
                Matcher end = PRIVATE_KEY_END.matcher(line);
                if (end.find(begin.end())) {
                    result.add(line.substring(0, begin.start()) + marker("private-key") + line.substring(end.end()));
                } else {
                    result.add(line.substring(0, begin.start()) + marker("private-key"));
                    inPrivateKey = true;
                }
                continue;
            }
            result.add(redactLine(line));
        }
        return result;
    }

    public FileDiff redact(FileDiff diff) {
        return diff.mapContent(this::redactLines);
    }

    /** High-confidence secrets on added lines, at most one per line. */
    public List<SecretHit> scan(FileDiff diff) {
        List<SecretHit> hits = new ArrayList<>();
        for (FileDiff.Line line : diff.addedLines()) {
            String content = line.content();
            if (PRIVATE_KEY_BEGIN.matcher(content).find()) {
                hits.add(new SecretHit(line.newNumber(), "private key"));
                continue;
            }
            for (Rule rule : RULES) {
                if (rule.reportable() && rule.pattern().matcher(content).find()) {
                    hits.add(new SecretHit(line.newNumber(), rule.label()));
                    break;
                }
            }
        }
        return hits;
    }

    public static boolean containsRedaction(String text) {
        return text != null && text.contains(MARKER_PREFIX);
    }

    private static String redactLine(String line) {
        String current = line;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(current);
            if (!matcher.find()) {
                continue;
            }
            StringBuilder out = new StringBuilder();
            do {
                if (rule.group() == 0) {
                    matcher.appendReplacement(out, Matcher.quoteReplacement(marker(rule.id())));
                } else {
                    String value = matcher.group(rule.group());
                    String whole = matcher.group();
                    String replacement = isPlaceholder(value) ? whole
                            : whole.substring(0, matcher.start(rule.group()) - matcher.start())
                                    + marker(rule.id())
                                    + whole.substring(matcher.end(rule.group()) - matcher.start());
                    matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
                }
            } while (matcher.find());
            matcher.appendTail(out);
            current = out.toString();
        }
        return current;
    }

    private static boolean isPlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("$") || value.contains("{") || value.contains("<") || value.contains("%")
                || value.startsWith(MARKER_PREFIX) || PLACEHOLDERS.contains(lower)
                || lower.chars().distinct().count() <= 2;
    }

    private static String marker(String id) {
        return MARKER_PREFIX + id + "]";
    }
}
