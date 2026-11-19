package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.FileDiff;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    private final Chunker chunker = new Chunker(2000);
    private final DiffParser parser = new DiffParser();

    @Test
    void leavesSmallDiffsWhole() {
        FileDiff diff = parser.parse("a.py", "@@ -1,1 +1,2 @@\n x = 1\n+y = 2");

        assertThat(chunker.split(diff)).singleElement().satisfies(chunk -> {
            assertThat(chunk.parts()).isEqualTo(1);
            assertThat(chunk.diff()).isEqualTo(diff);
        });
    }

    @Test
    void splitsAtHunkBoundaries() {
        StringBuilder patch = new StringBuilder();
        for (int hunk = 0; hunk < 6; hunk++) {
            int start = 1 + hunk * 100;
            patch.append("@@ -").append(start).append(",20 +").append(start).append(",20 @@\n")
                    .append(IntStream.range(0, 20).mapToObj(i -> " line of context number " + i).collect(Collectors.joining("\n")))
                    .append('\n');
        }
        FileDiff diff = parser.parse("big.java", patch.toString());

        List<Chunker.Chunk> chunks = chunker.split(diff);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.diff().render().length()).isLessThanOrEqualTo(2000));
        assertThat(chunks.stream().mapToInt(c -> c.diff().hunks().size()).sum()).isEqualTo(6);
        assertThat(chunks.get(chunks.size() - 1).part()).isEqualTo(chunks.size());
    }

    @Test
    void splitsAHugeHunkBetweenLinesAndKeepsLineNumbers() {
        String added = IntStream.rangeClosed(1, 120).mapToObj(i -> "+    statement number " + i + ";").collect(Collectors.joining("\n"));
        FileDiff diff = parser.parse("Huge.java", "@@ -0,0 +1,120 @@\n" + added);

        List<Chunker.Chunk> chunks = chunker.split(diff);

        assertThat(chunks).hasSizeGreaterThan(1);
        FileDiff second = chunks.get(1).diff();
        int firstLineOfSecond = second.hunks().get(0).lines().get(0).newNumber();
        assertThat(second.rightSideLine(firstLineOfSecond)).hasValueSatisfying(line ->
                assertThat(line.content()).isEqualTo("    statement number " + firstLineOfSecond + ";"));
        assertThat(chunks.stream().mapToInt(c -> c.diff().addedCount()).sum()).isEqualTo(120);
    }
}
