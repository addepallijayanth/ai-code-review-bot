package com.example.reviewbot.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.ai.agent.ReviewSession;
import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.review.SecretRedactor;
import com.example.reviewbot.support.ClaudeFixtures;
import com.example.reviewbot.support.TestProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportFindingToolTest {

    private static final String PATH = "app/views.py";

    private final ReportFindingTool tool = new ReportFindingTool();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        FileDiff diff = new DiffParser().parse(PATH, String.join("\n",
                "@@ -10,2 +10,5 @@ def search(request):",
                "     term = request.GET['q']",
                "+    sql = f\"SELECT * FROM items WHERE name = '{term}'\"",
                "+    rows = db.execute(sql)",
                "+    return render(rows)",
                "     # end"));
        RepoAccess none = new RepoAccess() {
            @Override
            public Optional<String> readFile(String path) {
                return Optional.empty();
            }

            @Override
            public List<String> listFiles() {
                return List.of();
            }

            @Override
            public List<SearchHit> searchCode(String query, int limit) {
                return List.of();
            }
        };
        ReviewSession session = new ReviewSession(none, TokenBudget.unlimited(), Set.of(PATH), new SecretRedactor(),
                TestProperties.ai().agent());
        context = new ToolContext(session, diff);
    }

    @Test
    void recordsAValidFinding() {
        ToolResult result = run(input(11, null, "cursor.execute(\"SELECT * FROM items WHERE name = %s\", [term])"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("Recorded finding 1 (critical) at app/views.py:11.");
        assertThat(context.findings()).singleElement().satisfies(f -> {
            assertThat(f.lineContent()).contains("SELECT * FROM items");
            assertThat(f.suggestion()).startsWith("cursor.execute");
        });
    }

    @Test
    void rejectsAnotherFileAndLinesFarFromTheDiff() {
        Map<String, Object> otherFile = input(11, null, null);
        otherFile.put("file", "app/models.py");
        assertThat(run(otherFile).error()).isTrue();

        ToolResult farAway = run(input(30, null, null));
        assertThat(farAway.error()).isTrue();
        assertThat(farAway.content()).contains("Lines you can report on: 10-14");
        assertThat(context.findings()).isEmpty();
    }

    @Test
    void acceptsLinesNextToAHunkAndNormalisesReversedRanges() {
        assertThat(run(input(15, null, null)).error()).isFalse();
        assertThat(run(input(11, 13, null)).error()).isFalse();
        assertThat(context.findings().get(1).startLine()).isEqualTo(11);
        assertThat(context.findings().get(1).line()).isEqualTo(13);
    }

    @Test
    void dropsSuggestionsThatContainRedactedText() {
        ToolResult result = run(input(11, null, "token = \"[REDACTED:credential]\""));

        assertThat(result.content()).contains("suggestion was dropped");
        assertThat(context.findings().get(0).suggestion()).isNull();
    }

    @Test
    void stripsCodeFencesAroundSuggestions() {
        assertThat(ReportFindingTool.normalizeSuggestion("```python\nx = 1\n```\n")).isEqualTo("x = 1");
        assertThat(ReportFindingTool.normalizeSuggestion("   ")).isNull();
    }

    private ToolResult run(Map<String, Object> input) {
        return tool.execute(ClaudeFixtures.toolUseBlock("report_finding", input), context);
    }

    private static Map<String, Object> input(int line, Integer startLine, String suggestion) {
        Map<String, Object> input = new HashMap<>();
        input.put("file", PATH);
        input.put("line", line);
        if (startLine != null) {
            input.put("start_line", startLine);
        }
        input.put("severity", "critical");
        input.put("category", "security");
        input.put("title", "SQL injection through the search term");
        input.put("message", "The query is built with an f-string from request input.");
        if (suggestion != null) {
            input.put("suggestion", suggestion);
        }
        return input;
    }
}
