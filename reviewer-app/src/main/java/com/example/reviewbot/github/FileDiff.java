package com.example.reviewbot.github;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The parsed diff of one file. Line numbers on the right side (context and added lines) are the
 * ones review comments can be anchored to.
 */
public record FileDiff(String path, List<Hunk> hunks) {

    public FileDiff {
        hunks = List.copyOf(hunks);
    }

    public enum LineType { CONTEXT, ADDED, REMOVED }

    /** One diff line. {@code oldNumber} is null for added lines, {@code newNumber} for removed ones. */
    public record Line(LineType type, Integer oldNumber, Integer newNumber, String content) {

        public boolean isRightSide() {
            return type != LineType.REMOVED;
        }

        Line withContent(String newContent) {
            return new Line(type, oldNumber, newNumber, newContent);
        }
    }

    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, String header, List<Line> lines) {

        public Hunk {
            lines = List.copyOf(lines);
        }

        public boolean containsNewLine(int number) {
            return lines.stream().anyMatch(l -> l.isRightSide() && Objects.equals(l.newNumber(), number));
        }

        void render(StringBuilder out) {
            out.append(header).append('\n');
            for (Line line : lines) {
                String number = line.newNumber() == null ? "" : line.newNumber().toString();
                out.append(" ".repeat(Math.max(0, 6 - number.length()))).append(number).append(' ')
                        .append(switch (line.type()) {
                            case ADDED -> '+';
                            case REMOVED -> '-';
                            case CONTEXT -> ' ';
                        })
                        .append("| ").append(line.content()).append('\n');
            }
        }
    }

    public Optional<Line> rightSideLine(int number) {
        for (Hunk hunk : hunks) {
            for (Line line : hunk.lines()) {
                if (line.isRightSide() && Objects.equals(line.newNumber(), number)) {
                    return Optional.of(line);
                }
            }
        }
        return Optional.empty();
    }

    /** True when GitHub accepts a review comment on this line of the new file. */
    public boolean isCommentable(int number) {
        return rightSideLine(number).isPresent();
    }

    public Optional<Hunk> hunkWithNewLine(int number) {
        return hunks.stream().filter(h -> h.containsNewLine(number)).findFirst();
    }

    public NavigableSet<Integer> commentableLines() {
        NavigableSet<Integer> lines = new TreeSet<>();
        for (Hunk hunk : hunks) {
            for (Line line : hunk.lines()) {
                if (line.isRightSide()) {
                    lines.add(line.newNumber());
                }
            }
        }
        return lines;
    }

    public List<Line> addedLines() {
        return hunks.stream().flatMap(h -> h.lines().stream()).filter(l -> l.type() == LineType.ADDED).toList();
    }

    public int addedCount() {
        return addedLines().size();
    }

    public int removedCount() {
        return (int) hunks.stream().flatMap(h -> h.lines().stream()).filter(l -> l.type() == LineType.REMOVED).count();
    }

    public boolean isEmpty() {
        return hunks.isEmpty();
    }

    /**
     * Diff text for the model, with the new-file line number in the left column so findings can
     * be reported against real line numbers:
     * <pre>
     * @@ -10,4 +10,5 @@ class Foo
     *     10  | unchanged line
     *     11 +| added line
     *        -| removed line
     * </pre>
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        hunks.forEach(h -> h.render(out));
        return out.toString();
    }

    /** Commentable lines as compact ranges ("10-18, 40-47") for error messages to the model. */
    public String commentableRanges() {
        List<String> ranges = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        for (int line : commentableLines()) {
            if (start == null) {
                start = line;
            } else if (line != previous + 1) {
                ranges.add(start.equals(previous) ? start.toString() : start + "-" + previous);
                start = line;
            }
            previous = line;
        }
        if (start != null) {
            ranges.add(start.equals(previous) ? start.toString() : start + "-" + previous);
        }
        return String.join(", ", ranges);
    }

    /**
     * Returns a copy whose line contents went through {@code transform}. The transform receives all
     * line contents in diff order and must return a list of the same size (used for redaction,
     * which needs to see consecutive lines of a multi-line secret).
     */
    public FileDiff mapContent(UnaryOperator<List<String>> transform) {
        List<String> contents = hunks.stream().flatMap(h -> h.lines().stream()).map(Line::content).toList();
        List<String> mapped = transform.apply(contents);
        if (mapped.size() != contents.size()) {
            throw new IllegalArgumentException("Content transform must keep the number of lines");
        }
        List<Hunk> result = new ArrayList<>(hunks.size());
        int index = 0;
        for (Hunk hunk : hunks) {
            List<Line> lines = new ArrayList<>(hunk.lines().size());
            for (Line line : hunk.lines()) {
                lines.add(line.withContent(mapped.get(index++)));
            }
            result.add(new Hunk(hunk.oldStart(), hunk.oldCount(), hunk.newStart(), hunk.newCount(), hunk.header(), lines));
        }
        return new FileDiff(path, result);
    }
}
