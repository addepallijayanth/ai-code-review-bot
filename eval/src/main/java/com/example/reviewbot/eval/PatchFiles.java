package com.example.reviewbot.eval;

import com.example.reviewbot.github.dto.ChangedFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a unified git diff into per-file entries shaped like GitHub's "list pull request files"
 * response: path, status, counts, and the hunks without file headers.
 */
final class PatchFiles {

    private PatchFiles() {
    }

    static List<ChangedFile> split(String unifiedDiff) {
        List<ChangedFile> files = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String line : unifiedDiff.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("diff --git ") && !block.isEmpty()) {
                files.add(toFile(block));
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(l -> l.startsWith("diff --git "))) {
            files.add(toFile(block));
        }
        return files;
    }

    private static ChangedFile toFile(List<String> block) {
        String oldPath = null;
        String newPath = null;
        boolean renamed = false;
        StringBuilder patch = new StringBuilder();
        boolean inHunks = false;
        int additions = 0;
        int deletions = 0;
        for (String line : block) {
            if (!inHunks) {
                if (line.startsWith("--- ")) {
                    oldPath = path(line.substring(4), "a/");
                } else if (line.startsWith("+++ ")) {
                    newPath = path(line.substring(4), "b/");
                } else if (line.startsWith("rename from ")) {
                    renamed = true;
                } else if (line.startsWith("@@")) {
                    inHunks = true;
                }
            }
            if (inHunks) {
                if (line.startsWith("+")) {
                    additions++;
                } else if (line.startsWith("-")) {
                    deletions++;
                }
                patch.append(line).append('\n');
            }
        }
        String status = oldPath == null ? "added" : newPath == null ? "removed" : renamed ? "renamed" : "modified";
        String filename = newPath != null ? newPath : oldPath;
        String text = patch.toString().stripTrailing();
        return new ChangedFile(filename, status, additions, deletions, additions + deletions,
                text.isEmpty() ? null : text, renamed ? oldPath : null);
    }

    private static String path(String value, String prefix) {
        String trimmed = value.strip();
        if (trimmed.equals("/dev/null")) {
            return null;
        }
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
    }
}
