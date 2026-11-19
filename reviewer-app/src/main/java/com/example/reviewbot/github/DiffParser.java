package com.example.reviewbot.github;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses the per-file {@code patch} GitHub returns for a pull request (unified diff hunks without
 * the {@code ---}/{@code +++} file headers) into {@link FileDiff}.
 */
@Component
public class DiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public FileDiff parse(String path, String patch) {
        List<FileDiff.Hunk> hunks = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return new FileDiff(path, hunks);
        }
        HunkBuilder current = null;
        for (String raw : patch.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    hunks.add(current.build());
                }
                current = new HunkBuilder(
                        Integer.parseInt(header.group(1)), count(header.group(2)),
                        Integer.parseInt(header.group(3)), count(header.group(4)),
                        line);
                continue;
            }
            if (current == null || line.startsWith("\\")) {
                // text before the first hunk, or "\ No newline at end of file"
                continue;
            }
            if (line.startsWith("+")) {
                current.added(line.substring(1));
            } else if (line.startsWith("-")) {
                current.removed(line.substring(1));
            } else if (line.startsWith(" ")) {
                current.context(line.substring(1));
            } else if (line.isEmpty() && current.expectsMore()) {
                // a blank context line whose leading space was stripped along the way
                current.context("");
            }
        }
        if (current != null) {
            hunks.add(current.build());
        }
        return new FileDiff(path, hunks);
    }

    private static int count(String group) {
        return group == null ? 1 : Integer.parseInt(group);
    }

    private static final class HunkBuilder {

        private final int oldStart;
        private final int oldCount;
        private final int newStart;
        private final int newCount;
        private final String header;
        private final List<FileDiff.Line> lines = new ArrayList<>();
        private int nextOld;
        private int nextNew;

        HunkBuilder(int oldStart, int oldCount, int newStart, int newCount, String header) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.header = header;
            this.nextOld = oldStart;
            this.nextNew = newStart;
        }

        void added(String content) {
            lines.add(new FileDiff.Line(FileDiff.LineType.ADDED, null, nextNew++, content));
        }

        void removed(String content) {
            lines.add(new FileDiff.Line(FileDiff.LineType.REMOVED, nextOld++, null, content));
        }

        void context(String content) {
            lines.add(new FileDiff.Line(FileDiff.LineType.CONTEXT, nextOld++, nextNew++, content));
        }

        boolean expectsMore() {
            return nextOld < oldStart + oldCount || nextNew < newStart + newCount;
        }

        FileDiff.Hunk build() {
            return new FileDiff.Hunk(oldStart, oldCount, newStart, newCount, header, lines);
        }
    }
}
