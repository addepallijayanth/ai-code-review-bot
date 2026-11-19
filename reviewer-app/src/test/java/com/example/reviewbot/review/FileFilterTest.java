package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.github.dto.ChangedFile;
import com.example.reviewbot.model.Severity;
import com.example.reviewbot.support.TestProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FileFilterTest {

    private final FileFilter filter = new FileFilter(TestProperties.review());

    @Test
    void keepsOrdinarySourceFiles() {
        FileFilter.Result result = filter.filter(List.of(file("src/main/java/App.java", 12)), RepoConfig.defaults(Severity.MINOR, 15));
        assertThat(result.accepted()).extracting(ChangedFile::filename).containsExactly("src/main/java/App.java");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void skipsFilesThatAreNotWorthAReview() {
        List<ChangedFile> files = List.of(
                file("package-lock.json", 300),
                file("backend/poetry.lock", 40),
                file("assets/logo.png", 0),
                file("api/generated/Client.java", 20),
                file("web/static/app.min.js", 5),
                file("web/node_modules/left-pad/index.js", 3),
                removed("src/Old.java"),
                file("src/Huge.java", 5000),
                new ChangedFile("src/Renamed.java", "renamed", 0, 0, 0, null, "src/Before.java"),
                new ChangedFile("src/Binary.dat", "modified", 0, 0, 0, null, null));

        Map<String, FileFilter.SkipReason> reasons = reasons(filter.filter(files, RepoConfig.defaults(Severity.MINOR, 15)));

        assertThat(reasons).containsExactlyInAnyOrderEntriesOf(Map.of(
                "package-lock.json", FileFilter.SkipReason.LOCKFILE,
                "backend/poetry.lock", FileFilter.SkipReason.LOCKFILE,
                "assets/logo.png", FileFilter.SkipReason.BINARY,
                "api/generated/Client.java", FileFilter.SkipReason.GENERATED,
                "web/static/app.min.js", FileFilter.SkipReason.GENERATED,
                "web/node_modules/left-pad/index.js", FileFilter.SkipReason.GENERATED,
                "src/Old.java", FileFilter.SkipReason.REMOVED,
                "src/Huge.java", FileFilter.SkipReason.TOO_LARGE,
                "src/Renamed.java", FileFilter.SkipReason.RENAME_ONLY,
                "src/Binary.dat", FileFilter.SkipReason.NO_DIFF));
    }

    @Test
    void appliesIgnorePatternsFromTheRepositoryConfig() {
        RepoConfig config = new RepoConfig(Severity.MINOR, List.of("docs/", "**/*.sql", "/scripts/**"), Set.of(), Map.of(), 15, List.of());
        List<ChangedFile> files = List.of(
                file("docs/guide/setup.md", 4),
                file("db/migration/V2__users.sql", 9),
                file("scripts/release.sh", 2),
                file("tools/scripts/build.sh", 2));

        Map<String, FileFilter.SkipReason> reasons = reasons(filter.filter(files, config));

        assertThat(reasons).containsOnlyKeys("docs/guide/setup.md", "db/migration/V2__users.sql", "scripts/release.sh");
        assertThat(reasons.values()).containsOnly(FileFilter.SkipReason.IGNORED);
    }

    @Test
    void capsTheNumberOfFilesPerReview() {
        List<ChangedFile> files = java.util.stream.IntStream.rangeClosed(1, 55).mapToObj(i -> file("src/F" + i + ".java", 1)).toList();

        FileFilter.Result result = filter.filter(files, RepoConfig.defaults(Severity.MINOR, 15));

        assertThat(result.accepted()).hasSize(50);
        assertThat(result.skipped()).hasSize(5).allSatisfy(s -> assertThat(s.reason()).isEqualTo(FileFilter.SkipReason.FILE_LIMIT));
    }

    private static Map<String, FileFilter.SkipReason> reasons(FileFilter.Result result) {
        return result.skipped().stream().collect(java.util.stream.Collectors.toMap(FileFilter.SkippedFile::path, FileFilter.SkippedFile::reason));
    }

    private static ChangedFile file(String path, int changes) {
        return new ChangedFile(path, "modified", changes, 0, changes, "@@ -1,1 +1,1 @@\n-a\n+b", null);
    }

    private static ChangedFile removed(String path) {
        return new ChangedFile(path, "removed", 0, 10, 10, "@@ -1,1 +0,0 @@\n-a", null);
    }
}
