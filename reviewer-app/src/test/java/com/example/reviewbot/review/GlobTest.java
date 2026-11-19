package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource({
            "**/generated/**,      src/generated/Api.java,        true",
            "**/generated/**,      generated/Api.java,            true",
            "**/generated/**,      src/generator/Api.java,        false",
            "**/*.lock,            yarn.lock,                     true",
            "**/*.lock,            a/b/Cargo.lock,                true",
            "*.min.js,             web/app.min.js,                true",
            "*.min.js,             web/app.js,                    false",
            "docs/,                docs/index.md,                 true",
            "docs/,                site/docs/index.md,            true",
            "/build/**,            build/out.txt,                 true",
            "/build/**,            src/build/out.txt,             false",
            "src/legacy/**,        src/legacy/Old.java,           true",
            "src/legacy/**,        lib/src/legacy/Old.java,       false",
            "src/*.java,           src/App.java,                  true",
            "src/*.java,           src/app/App.java,              false",
            "'**/*.{yml,yaml}',    deploy/app.yaml,               true",
            "'**/*.{yml,yaml}',    deploy/app.json,               false",
            "file?.txt,            dir/file1.txt,                 true",
            "a+b.txt,              a+b.txt,                       true"
    })
    void matchesLikeGitignore(String glob, String path, boolean expected) {
        assertThat(Glob.compile(glob).matches(path)).isEqualTo(expected);
    }
}
