package com.example.reviewbot.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PositionMapperTest {

    private final PositionMapper mapper = new PositionMapper();
    private final FileDiff diff = new DiffParser().parse("src/OrderService.java", DiffParserTest.PATCH);

    @Test
    void mapsALineInsideTheDiffExactly() {
        assertThat(mapper.map(diff, 12, null)).hasValue(new CommentAnchor("src/OrderService.java", 12, null, true));
    }

    @Test
    void contextLinesAreCommentableToo() {
        assertThat(mapper.map(diff, 10, null)).hasValueSatisfying(anchor -> assertThat(anchor.exact()).isTrue());
    }

    @Test
    void snapsLinesJustOutsideAHunkAndMarksThemInexact() {
        assertThat(mapper.map(diff, 17, null)).hasValue(new CommentAnchor("src/OrderService.java", 16, null, false));
        assertThat(mapper.map(diff, 39, null)).hasValue(new CommentAnchor("src/OrderService.java", 41, null, false));
    }

    @Test
    void hasNoPositionForLinesFarFromTheDiff() {
        assertThat(mapper.map(diff, 25, null)).isEmpty();
        assertThat(mapper.map(diff, 1, null)).isEmpty();
    }

    @Test
    void keepsARangeInsideOneHunk() {
        assertThat(mapper.map(diff, 13, 11)).hasValue(new CommentAnchor("src/OrderService.java", 13, 11, true));
    }

    @Test
    void collapsesARangeThatSpansHunks() {
        assertThat(mapper.map(diff, 42, 12)).hasValue(new CommentAnchor("src/OrderService.java", 42, null, false));
    }

    @Test
    void collapsesARangeWhoseStartIsOutsideTheDiff() {
        assertThat(mapper.map(diff, 11, 5)).hasValue(new CommentAnchor("src/OrderService.java", 11, null, false));
    }
}
