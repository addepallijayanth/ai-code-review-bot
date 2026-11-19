package com.example.reviewbot.review;

import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.dto.ChangedFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides which changed files are worth sending to the model. Lockfiles, binaries, generated and
 * vendored code, huge diffs and anything matched by {@code ignore:} in {@code .reviewbot.yml}
 * are skipped, each with a reason that is listed in the review summary.
 */
@Component
public class FileFilter {

    public enum SkipReason {
        REMOVED("deleted"),
        IGNORED("ignored by .reviewbot.yml"),
        LOCKFILE("lockfile"),
        BINARY("binary file"),
        GENERATED("generated or vendored"),
        RENAME_ONLY("renamed without changes"),
        NO_DIFF("no diff available (binary or too large for GitHub to show)"),
        TOO_LARGE("too many changed lines"),
        FILE_LIMIT("over the per-review file limit");

        private final String label;

        SkipReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record SkippedFile(String path, SkipReason reason) {
    }

    public record Result(List<ChangedFile> accepted, List<SkippedFile> skipped) {
    }

    private static final Set<String> LOCKFILES = Set.of(
            "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "bun.lock",
            "composer.lock", "gemfile.lock", "cargo.lock", "poetry.lock", "pipfile.lock", "uv.lock", "pdm.lock",
            "go.sum", "gradle.lockfile", "packages.lock.json", "podfile.lock", "mix.lock", "pubspec.lock",
            "flake.lock", "deno.lock", "paket.lock");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "tiff", "psd", "pdf", "zip", "gz", "tgz", "bz2", "xz",
            "7z", "rar", "jar", "war", "ear", "class", "dll", "exe", "so", "dylib", "bin", "o", "a", "lib", "pyc",
            "wasm", "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4", "mov", "avi", "wav", "ogg", "webm",
            "sqlite", "db", "jks", "keystore", "p12", "pfx");

    private static final List<Glob> GENERATED = Glob.compileAll(List.of(
            "**/generated/**", "**/*.generated.*", "**/*.min.js", "**/*.min.css", "**/*.map", "**/*.pb.go",
            "**/*_pb2.py", "**/*_pb2_grpc.py", "**/*.g.dart", "**/*.freezed.dart", "**/*.designer.cs",
            "**/__snapshots__/**", "**/*.snap", "**/node_modules/**", "**/vendor/**"));

    private final ReviewProperties properties;

    public FileFilter(ReviewProperties properties) {
        this.properties = properties;
    }

    public Result filter(List<ChangedFile> files, RepoConfig config) {
        List<Glob> ignored = config.ignoreGlobs();
        List<ChangedFile> accepted = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        for (ChangedFile file : files) {
            SkipReason reason = classify(file, ignored);
            if (reason == null && accepted.size() >= properties.maxFiles()) {
                reason = SkipReason.FILE_LIMIT;
            }
            if (reason == null) {
                accepted.add(file);
            } else {
                skipped.add(new SkippedFile(file.filename(), reason));
            }
        }
        return new Result(accepted, skipped);
    }

    SkipReason classify(ChangedFile file, List<Glob> ignored) {
        if (file.isRemoved()) {
            return SkipReason.REMOVED;
        }
        String path = file.filename();
        String name = Languages.fileName(path).toLowerCase(Locale.ROOT);
        if (Glob.anyMatches(ignored, path)) {
            return SkipReason.IGNORED;
        }
        if (LOCKFILES.contains(name) || name.endsWith(".lock")) {
            return SkipReason.LOCKFILE;
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1))) {
            return SkipReason.BINARY;
        }
        if (Glob.anyMatches(GENERATED, path)) {
            return SkipReason.GENERATED;
        }
        if (!file.hasPatch()) {
            return file.isRenamed() && file.changes() == 0 ? SkipReason.RENAME_ONLY : SkipReason.NO_DIFF;
        }
        if (file.changes() > properties.maxChangedLinesPerFile()) {
            return SkipReason.TOO_LARGE;
        }
        return null;
    }
}
