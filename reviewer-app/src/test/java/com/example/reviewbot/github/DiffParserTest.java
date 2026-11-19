package com.example.reviewbot.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    static final String PATCH = String.join("\n",
            "@@ -10,6 +10,7 @@ public class OrderService {",
            "     public Order find(long id) {",
            "-        return orders.get(id);",
            "+        Order order = orders.get(id);",
            "+        return order;",
            "     }",
            " ",
            "     public void save(Order order) {",
            "         orders.put(order.id(), order);",
            "@@ -40,3 +41,4 @@ public class OrderService {",
            "     void close() {",
            "+        pool.shutdown();",
            "     }",
            " }",
            "\\ No newline at end of file");

    @Test
    void numbersEveryLineOnBothSides() {
        FileDiff diff = parser.parse("src/OrderService.java", PATCH);

        assertThat(diff.hunks()).hasSize(2);
        List<FileDiff.Line> first = diff.hunks().get(0).lines();
        assertThat(first).extracting(FileDiff.Line::type).containsExactly(
                FileDiff.LineType.CONTEXT, FileDiff.LineType.REMOVED, FileDiff.LineType.ADDED, FileDiff.LineType.ADDED,
                FileDiff.LineType.CONTEXT, FileDiff.LineType.CONTEXT, FileDiff.LineType.CONTEXT, FileDiff.LineType.CONTEXT);
        assertThat(first.get(1).oldNumber()).isEqualTo(11);
        assertThat(first.get(1).newNumber()).isNull();
        assertThat(first.get(2).newNumber()).isEqualTo(11);
        assertThat(first.get(3).newNumber()).isEqualTo(12);
        assertThat(first.get(4).newNumber()).isEqualTo(13);
        assertThat(first.get(4).oldNumber()).isEqualTo(12);

        FileDiff.Hunk second = diff.hunks().get(1);
        assertThat(second.newStart()).isEqualTo(41);
        assertThat(second.lines()).hasSize(4);
        assertThat(second.lines().get(1).newNumber()).isEqualTo(42);
    }

    @Test
    void knowsWhichLinesCanCarryAComment() {
        FileDiff diff = parser.parse("src/OrderService.java", PATCH);

        assertThat(diff.isCommentable(11)).isTrue();
        assertThat(diff.isCommentable(16)).isTrue();
        assertThat(diff.isCommentable(17)).isFalse();
        assertThat(diff.isCommentable(42)).isTrue();
        assertThat(diff.commentableRanges()).isEqualTo("10-16, 41-44");
        assertThat(diff.addedCount()).isEqualTo(3);
        assertThat(diff.removedCount()).isEqualTo(1);
    }

    @Test
    void rendersNewLineNumbersInTheLeftColumn() {
        String rendered = parser.parse("src/OrderService.java", PATCH).render();

        assertThat(rendered).contains("@@ -10,6 +10,7 @@ public class OrderService {\n");
        assertThat(rendered).contains("    11 +|         Order order = orders.get(id);\n");
        assertThat(rendered).contains("       -|         return orders.get(id);\n");
        assertThat(rendered).contains("    10  |     public Order find(long id) {\n");
        assertThat(rendered).doesNotContain("No newline");
    }

    @Test
    void handlesNewFilesAndMissingCounts() {
        FileDiff diff = parser.parse("README.md", "@@ -0,0 +1 @@\n+hello");

        assertThat(diff.hunks()).singleElement().satisfies(hunk -> {
            assertThat(hunk.newStart()).isEqualTo(1);
            assertThat(hunk.newCount()).isEqualTo(1);
        });
        assertThat(diff.isCommentable(1)).isTrue();
    }

    @Test
    void toleratesWindowsLineEndingsAndEmptyPatches() {
        FileDiff crlf = parser.parse("a.txt", "@@ -1,2 +1,2 @@\r\n-old\r\n+new\r\n same\r\n");
        assertThat(crlf.rightSideLine(1)).hasValueSatisfying(line -> assertThat(line.content()).isEqualTo("new"));
        assertThat(crlf.rightSideLine(2)).hasValueSatisfying(line -> assertThat(line.content()).isEqualTo("same"));

        assertThat(parser.parse("b.txt", null).isEmpty()).isTrue();
        assertThat(parser.parse("b.txt", "   ").isEmpty()).isTrue();
    }

    @Test
    void mapContentKeepsStructure() {
        FileDiff diff = parser.parse("src/OrderService.java", PATCH);
        FileDiff upper = diff.mapContent(lines -> lines.stream().map(String::toUpperCase).toList());

        assertThat(upper.commentableLines()).isEqualTo(diff.commentableLines());
        assertThat(upper.rightSideLine(11)).hasValueSatisfying(line -> assertThat(line.content()).contains("ORDER ORDER"));
    }
}
