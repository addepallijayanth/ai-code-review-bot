package com.example.reviewbot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.github.dto.ChangedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps the dataset honest without calling the API: every seeded bug must sit on a line the bot can comment on. */
class DatasetTest {

    private static final Path DATASET = Path.of("dataset");

    @Test
    void hasEnoughSeededBugsAndCleanDiffs() {
        List<EvalCase> cases = EvalCase.loadAll(DATASET, null);

        assertThat(cases.stream().filter(c -> !c.isClean()).count()).isGreaterThanOrEqualTo(20);
        assertThat(cases.stream().filter(EvalCase::isClean).count()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void everySeededBugIsOnACommentableLineOfAChangedFile() {
        DiffParser parser = new DiffParser();
        for (EvalCase evalCase : EvalCase.loadAll(DATASET, null)) {
            assertThat(evalCase.files()).as(evalCase.id() + " has changed files").isNotEmpty();
            for (EvalCase.ExpectedIssue issue : evalCase.expected()) {
                ChangedFile file = evalCase.files().stream()
                        .filter(f -> f.filename().equals(issue.file()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(evalCase.id() + ": " + issue.file() + " is not in diff.patch"));
                FileDiff diff = parser.parse(file.filename(), file.patch());
                for (int line : issue.lines()) {
                    assertThat(diff.isCommentable(line))
                            .as("%s: line %d of %s is in the diff", evalCase.id(), line, issue.file())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void changedFilesMatchTheRepositorySnapshot() throws Exception {
        for (EvalCase evalCase : EvalCase.loadAll(DATASET, null)) {
            for (ChangedFile file : evalCase.files()) {
                if (!file.isRemoved()) {
                    assertThat(evalCase.repoDir().resolve(file.filename()))
                            .as(evalCase.id() + " ships " + file.filename()).exists();
                    assertThat(Files.readString(evalCase.repoDir().resolve(file.filename()))).isNotBlank();
                }
            }
        }
    }

    @Test
    void splitsAMultiFilePatch() {
        List<ChangedFile> files = PatchFiles.split(String.join("\n",
                "diff --git a/src/A.java b/src/A.java",
                "index 1111111..2222222 100644",
                "--- a/src/A.java",
                "+++ b/src/A.java",
                "@@ -1,2 +1,2 @@",
                " class A {",
                "-}",
                "+ }",
                "diff --git a/docs/new.md b/docs/new.md",
                "new file mode 100644",
                "--- /dev/null",
                "+++ b/docs/new.md",
                "@@ -0,0 +1 @@",
                "+hello"));

        assertThat(files).hasSize(2);
        assertThat(files.get(0).filename()).isEqualTo("src/A.java");
        assertThat(files.get(0).status()).isEqualTo("modified");
        assertThat(files.get(0).additions()).isEqualTo(1);
        assertThat(files.get(0).deletions()).isEqualTo(1);
        assertThat(files.get(0).patch()).startsWith("@@ -1,2 +1,2 @@");
        assertThat(files.get(1).status()).isEqualTo("added");
        assertThat(files.get(1).filename()).isEqualTo("docs/new.md");
    }
}
