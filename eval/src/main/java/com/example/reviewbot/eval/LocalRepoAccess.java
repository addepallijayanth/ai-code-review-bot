package com.example.reviewbot.eval;

import com.example.reviewbot.ai.tools.RepoAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** {@link RepoAccess} over a case's {@code repo/} directory, standing in for the GitHub API. */
final class LocalRepoAccess implements RepoAccess {

    private final Path root;
    private final List<String> diffPaths;

    LocalRepoAccess(Path root, List<String> diffPaths) {
        this.root = root.toAbsolutePath().normalize();
        this.diffPaths = List.copyOf(diffPaths);
    }

    @Override
    public Optional<String> readFile(String path) {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> listFiles() {
        Set<String> paths = new LinkedHashSet<>(diffPaths);
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(paths::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return List.copyOf(paths);
    }

    /** Case-insensitive substring search, returning each hit with one line of context on both sides. */
    @Override
    public List<SearchHit> searchCode(String query, int limit) {
        String needle = query.replace("\"", "").strip().toLowerCase(Locale.ROOT);
        List<SearchHit> hits = new ArrayList<>();
        for (String path : listFiles()) {
            Optional<String> content = readFile(path);
            if (content.isEmpty()) {
                continue;
            }
            List<String> lines = content.get().lines().toList();
            List<String> fragments = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                    fragments.add(String.join("\n", lines.subList(Math.max(0, i - 1), Math.min(lines.size(), i + 2))));
                }
            }
            if (!fragments.isEmpty()) {
                hits.add(new SearchHit(path, fragments.subList(0, Math.min(3, fragments.size()))));
                if (hits.size() >= limit) {
                    break;
                }
            }
        }
        return hits;
    }
}
