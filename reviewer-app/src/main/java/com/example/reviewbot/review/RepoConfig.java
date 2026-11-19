package com.example.reviewbot.review;

import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.Severity;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Effective review settings for one repository: {@code .reviewbot.yml} merged over the service
 * defaults. {@code warnings} explains anything in the file that was ignored.
 */
public record RepoConfig(
        Severity minSeverity,
        List<String> ignore,
        Set<Category> focus,
        Map<String, List<String>> languageRules,
        int maxComments,
        List<String> warnings) {

    public RepoConfig {
        ignore = List.copyOf(ignore);
        focus = Set.copyOf(focus);
        languageRules = Collections.unmodifiableMap(new LinkedHashMap<>(languageRules));
        warnings = List.copyOf(warnings);
    }

    public static RepoConfig defaults(Severity minSeverity, int maxComments) {
        return new RepoConfig(minSeverity, List.of(), Set.of(), Map.of(), maxComments, List.of());
    }

    public List<Glob> ignoreGlobs() {
        return Glob.compileAll(ignore);
    }

    /** Focus categories in a stable order, so prompts built from them are byte-identical. */
    public List<Category> focusInOrder() {
        return Arrays.stream(Category.values()).filter(focus::contains).toList();
    }

    public boolean hasCustomRules() {
        return !focus.isEmpty() || !languageRules.isEmpty();
    }
}
