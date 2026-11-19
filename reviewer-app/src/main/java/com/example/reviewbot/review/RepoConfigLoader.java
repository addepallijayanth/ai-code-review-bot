package com.example.reviewbot.review;

import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.GitHubClient;
import com.example.reviewbot.github.RepoRef;
import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads {@code .reviewbot.yml}. It is read from the pull request's base commit, so a pull request
 * cannot switch off or weaken its own review. Invalid settings never fail a review: they fall
 * back to defaults and are reported as warnings in the review summary.
 */
@Component
public class RepoConfigLoader {

    public static final List<String> CONFIG_FILES = List.of(".reviewbot.yml", ".reviewbot.yaml");

    private static final int MAX_CONFIG_CHARS = 32 * 1024;
    private static final int MAX_IGNORE_PATTERNS = 100;
    private static final int MAX_RULES_PER_LANGUAGE = 20;
    private static final int MAX_RULE_LENGTH = 300;
    private static final int MAX_COMMENTS_LIMIT = 50;
    private static final Set<String> KNOWN_KEYS = Set.of("min_severity", "ignore", "focus", "language_rules", "max_comments");

    private final YAMLMapper yaml = new YAMLMapper();
    private final ReviewProperties properties;

    public RepoConfigLoader(ReviewProperties properties) {
        this.properties = properties;
    }

    public RepoConfig load(GitHubClient gitHub, long installationId, RepoRef repo, String ref) {
        for (String file : CONFIG_FILES) {
            Optional<String> content = gitHub.getFileContent(installationId, repo, file, ref);
            if (content.isPresent()) {
                return parse(content.get());
            }
        }
        return defaults();
    }

    public RepoConfig defaults() {
        return RepoConfig.defaults(defaultSeverity(), properties.defaults().maxComments());
    }

    public RepoConfig parse(String text) {
        List<String> warnings = new ArrayList<>();
        if (text.length() > MAX_CONFIG_CHARS) {
            warnings.add(".reviewbot.yml is larger than 32 KB and was ignored.");
            return withWarnings(warnings);
        }
        JsonNode root;
        try {
            root = yaml.readTree(text);
        } catch (IOException e) {
            warnings.add(".reviewbot.yml is not valid YAML and was ignored (" + firstLine(e.getMessage()) + ").");
            return withWarnings(warnings);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return defaults();
        }
        if (!root.isObject()) {
            warnings.add(".reviewbot.yml must contain a mapping of settings; it was ignored.");
            return withWarnings(warnings);
        }
        root.fieldNames().forEachRemaining(key -> {
            if (!KNOWN_KEYS.contains(key)) {
                warnings.add("Unknown setting `" + key + "` was ignored.");
            }
        });

        Severity minSeverity = defaultSeverity();
        JsonNode severityNode = root.get("min_severity");
        if (severityNode != null && !severityNode.isNull()) {
            Optional<Severity> parsed = Severity.fromSlug(severityNode.asText());
            if (parsed.isPresent()) {
                minSeverity = parsed.get();
            } else {
                warnings.add("`min_severity: " + severityNode.asText() + "` is not one of critical, major, minor, "
                        + "suggestion; using `" + minSeverity.slug() + "`.");
            }
        }

        List<String> ignore = strings(root.get("ignore"), "ignore", MAX_IGNORE_PATTERNS, warnings);

        Set<Category> focus = EnumSet.noneOf(Category.class);
        for (String value : strings(root.get("focus"), "focus", Category.values().length, warnings)) {
            Optional<Category> category = Category.fromSlug(value);
            if (category.isPresent()) {
                focus.add(category.get());
            } else {
                warnings.add("Unknown focus `" + value + "` was ignored.");
            }
        }

        Map<String, List<String>> languageRules = new LinkedHashMap<>();
        JsonNode rulesNode = root.get("language_rules");
        if (rulesNode != null && !rulesNode.isNull()) {
            if (!rulesNode.isObject()) {
                warnings.add("`language_rules` must map a language to a list of rules; it was ignored.");
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = rulesNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    List<String> rules = strings(entry.getValue(), "language_rules." + entry.getKey(), MAX_RULES_PER_LANGUAGE, warnings)
                            .stream()
                            .map(rule -> rule.length() > MAX_RULE_LENGTH ? rule.substring(0, MAX_RULE_LENGTH) : rule)
                            .toList();
                    if (!rules.isEmpty()) {
                        languageRules.put(entry.getKey().trim().toLowerCase(Locale.ROOT), rules);
                    }
                }
            }
        }

        int maxComments = properties.defaults().maxComments();
        JsonNode commentsNode = root.get("max_comments");
        if (commentsNode != null && !commentsNode.isNull()) {
            if (commentsNode.isIntegralNumber() && commentsNode.canConvertToInt()) {
                int value = commentsNode.asInt();
                maxComments = Math.max(0, Math.min(MAX_COMMENTS_LIMIT, value));
                if (value != maxComments) {
                    warnings.add("`max_comments` must be between 0 and " + MAX_COMMENTS_LIMIT + "; using " + maxComments + ".");
                }
            } else {
                warnings.add("`max_comments` must be a whole number; using " + maxComments + ".");
            }
        }
        return new RepoConfig(minSeverity, ignore, focus, languageRules, maxComments, warnings);
    }

    private Severity defaultSeverity() {
        return Severity.fromSlug(properties.defaults().minSeverity()).orElse(Severity.MINOR);
    }

    private RepoConfig withWarnings(List<String> warnings) {
        RepoConfig defaults = defaults();
        return new RepoConfig(defaults.minSeverity(), defaults.ignore(), defaults.focus(), defaults.languageRules(),
                defaults.maxComments(), warnings);
    }

    private static List<String> strings(JsonNode node, String key, int max, List<String> warnings) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isTextual()) {
            values.add(node.asText().trim());
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isValueNode() && !item.asText().isBlank()) {
                    values.add(item.asText().trim());
                } else {
                    warnings.add("A non-text entry in `" + key + "` was ignored.");
                }
            }
        } else {
            warnings.add("`" + key + "` must be a list; it was ignored.");
            return values;
        }
        if (values.size() > max) {
            warnings.add("Only the first " + max + " entries of `" + key + "` are used.");
            return new ArrayList<>(values.subList(0, max));
        }
        return values;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "parse error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
