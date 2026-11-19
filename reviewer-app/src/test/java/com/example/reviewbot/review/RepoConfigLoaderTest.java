package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.Severity;
import com.example.reviewbot.support.TestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepoConfigLoaderTest {

    private final RepoConfigLoader loader = new RepoConfigLoader(TestProperties.review());

    @Test
    void readsTheDocumentedExample() {
        RepoConfig config = loader.parse("""
                min_severity: minor          # critical | major | minor | suggestion
                ignore:
                  - "**/generated/**"
                  - "**/*.lock"
                focus:
                  - security
                  - null-safety
                  - tests
                language_rules:
                  java:
                    - "Prefer Optional over returning null"
                    - "Close resources with try-with-resources"
                max_comments: 15
                """);

        assertThat(config.minSeverity()).isEqualTo(Severity.MINOR);
        assertThat(config.ignore()).containsExactly("**/generated/**", "**/*.lock");
        assertThat(config.focusInOrder()).containsExactly(Category.SECURITY, Category.NULL_SAFETY, Category.TESTS);
        assertThat(config.languageRules()).containsOnlyKeys("java");
        assertThat(config.languageRules().get("java")).hasSize(2);
        assertThat(config.maxComments()).isEqualTo(15);
        assertThat(config.warnings()).isEmpty();
    }

    @Test
    void fallsBackToDefaultsAndExplainsWhy() {
        RepoConfig config = loader.parse("""
                min_severity: blocker
                focus: [security, style]
                max_comments: 500
                auto_approve: true
                """);

        assertThat(config.minSeverity()).isEqualTo(Severity.MINOR);
        assertThat(config.focus()).containsExactly(Category.SECURITY);
        assertThat(config.maxComments()).isEqualTo(50);
        assertThat(config.warnings()).hasSize(4).anySatisfy(w -> assertThat(w).contains("auto_approve"))
                .anySatisfy(w -> assertThat(w).contains("blocker"))
                .anySatisfy(w -> assertThat(w).contains("style"))
                .anySatisfy(w -> assertThat(w).contains("max_comments"));
    }

    @Test
    void neverFailsOnBrokenYaml() {
        RepoConfig config = loader.parse("ignore: [unclosed\nfocus: :::");

        assertThat(config.minSeverity()).isEqualTo(Severity.MINOR);
        assertThat(config.maxComments()).isEqualTo(15);
        assertThat(config.warnings()).singleElement().satisfies(w -> assertThat(w).contains("not valid YAML"));
    }

    @Test
    void treatsAnEmptyFileAsDefaults() {
        assertThat(loader.parse("")).isEqualTo(loader.defaults());
        assertThat(loader.parse("# only a comment\n")).isEqualTo(loader.defaults());
    }

    @Test
    void acceptsASingleStringWhereAListIsExpected() {
        RepoConfig config = loader.parse("ignore: \"docs/**\"\nlanguage_rules:\n  Python: \"Use pathlib\"\n");

        assertThat(config.ignore()).containsExactly("docs/**");
        assertThat(config.languageRules()).containsEntry("python", List.of("Use pathlib"));
    }
}
