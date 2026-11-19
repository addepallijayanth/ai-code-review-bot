package com.example.reviewbot.review;

import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.FileDiff;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Splits a large file diff into parts the agent reviews separately. Hunks stay whole where
 * possible; a single hunk larger than the limit is cut between lines (line numbers are kept, so
 * findings still map to the right place).
 */
@Component
public class Chunker {

    public record Chunk(FileDiff diff, int part, int parts) {
    }

    private final int maxChars;

    @Autowired
    public Chunker(ReviewProperties properties) {
        this(properties.maxChunkChars());
    }

    Chunker(int maxChars) {
        this.maxChars = Math.max(1000, maxChars);
    }

    public List<Chunk> split(FileDiff diff) {
        if (diff.render().length() <= maxChars) {
            return List.of(new Chunk(diff, 1, 1));
        }
        List<List<FileDiff.Hunk>> groups = new ArrayList<>();
        List<FileDiff.Hunk> current = new ArrayList<>();
        int size = 0;
        for (FileDiff.Hunk hunk : diff.hunks()) {
            for (FileDiff.Hunk piece : splitHunk(diff.path(), hunk)) {
                int pieceSize = renderedLength(diff.path(), piece);
                if (!current.isEmpty() && size + pieceSize > maxChars) {
                    groups.add(current);
                    current = new ArrayList<>();
                    size = 0;
                }
                current.add(piece);
                size += pieceSize;
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        List<Chunk> chunks = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            chunks.add(new Chunk(new FileDiff(diff.path(), groups.get(i)), i + 1, groups.size()));
        }
        return chunks;
    }

    private List<FileDiff.Hunk> splitHunk(String path, FileDiff.Hunk hunk) {
        if (renderedLength(path, hunk) <= maxChars) {
            return List.of(hunk);
        }
        List<FileDiff.Hunk> pieces = new ArrayList<>();
        List<FileDiff.Line> lines = new ArrayList<>();
        int size = hunk.header().length();
        int part = 1;
        for (FileDiff.Line line : hunk.lines()) {
            int lineSize = line.content().length() + 10;
            if (!lines.isEmpty() && size + lineSize > maxChars) {
                pieces.add(piece(hunk, lines, part++));
                lines = new ArrayList<>();
                size = hunk.header().length();
            }
            lines.add(line);
            size += lineSize;
        }
        if (!lines.isEmpty()) {
            pieces.add(piece(hunk, lines, part));
        }
        return pieces;
    }

    private static FileDiff.Hunk piece(FileDiff.Hunk hunk, List<FileDiff.Line> lines, int part) {
        int oldStart = lines.stream().filter(l -> l.oldNumber() != null).map(FileDiff.Line::oldNumber).findFirst().orElse(hunk.oldStart());
        int newStart = lines.stream().filter(l -> l.newNumber() != null).map(FileDiff.Line::newNumber).findFirst().orElse(hunk.newStart());
        int oldCount = (int) lines.stream().filter(l -> l.oldNumber() != null).count();
        int newCount = (int) lines.stream().filter(l -> l.newNumber() != null).count();
        String header = "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@ (split hunk, piece " + part + ")";
        return new FileDiff.Hunk(oldStart, oldCount, newStart, newCount, header, lines);
    }

    private static int renderedLength(String path, FileDiff.Hunk hunk) {
        return new FileDiff(path, List.of(hunk)).render().length();
    }
}
