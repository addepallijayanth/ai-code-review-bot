package com.example.reviewbot.eval;

import com.example.reviewbot.github.dto.ChangedFile;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * One dataset case: {@code diff.patch} (a unified git diff), {@code expected.json} (the seeded
 * bugs, or none for a clean diff) and optionally {@code repo/} (the files after the change, for
 * the agent's read and search tools).
 */
public record EvalCase(String id, String title, String description, List<ExpectedIssue> expected,
                       List<ChangedFile> files, Path repoDir) {

    /**
     * A seeded bug. A finding counts when it is on {@code file} within {@code tolerance} lines of
     * {@code line}, or of one of {@code alsoLines} when the same problem can fairly be reported in
     * more than one place (a lock taken in the wrong order, a field and the method that misuses it).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedIssue(String file, int line, List<Integer> alsoLines, int tolerance, String category, String note) {

        public ExpectedIssue {
            alsoLines = alsoLines == null ? List.of() : List.copyOf(alsoLines);
            tolerance = tolerance <= 0 ? 3 : tolerance;
        }

        List<Integer> lines() {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(line), alsoLines.stream()).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Expectations(String title, String description, List<ExpectedIssue> expected) {
    }

    public boolean isClean() {
        return expected.isEmpty();
    }

    static List<EvalCase> loadAll(Path dataset, String only) {
        try (Stream<Path> dirs = Files.list(dataset)) {
            return dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("diff.patch")))
                    .filter(dir -> only == null || dir.getFileName().toString().equals(only))
                    .sorted()
                    .map(EvalCase::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read dataset " + dataset, e);
        }
    }

    static EvalCase load(Path dir) {
        try {
            Expectations expectations = new ObjectMapper().readValue(dir.resolve("expected.json").toFile(), Expectations.class);
            List<ChangedFile> files = PatchFiles.split(Files.readString(dir.resolve("diff.patch")));
            return new EvalCase(dir.getFileName().toString(),
                    expectations.title() == null ? dir.getFileName().toString() : expectations.title(),
                    expectations.description() == null ? "" : expectations.description(),
                    expectations.expected() == null ? List.of() : expectations.expected(),
                    files, dir.resolve("repo"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load eval case " + dir, e);
        }
    }
}
