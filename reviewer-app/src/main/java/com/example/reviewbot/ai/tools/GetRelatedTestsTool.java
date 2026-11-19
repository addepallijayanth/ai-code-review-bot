package com.example.reviewbot.ai.tools;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@code get_related_tests(path)}: does this code have tests, and does the pull request touch them? */
@Component
public class GetRelatedTestsTool implements ReviewTool {

    public static final String NAME = "get_related_tests";
    private static final int MAX_RESULTS = 8;

    private static final BetaTool DEFINITION = ToolSchemas.strictTool(NAME,
            "Find existing test files for a source file by naming convention (FooTest, FooIT, foo.test.ts, "
                    + "test_foo.py, foo_test.go, ...) and list the test files this pull request changes. Use it "
                    + "before reporting that changed behaviour is untested.",
            List.of("path"),
            ToolSchemas.string("path", "Repository-relative path of the source file"));

    record Input(String path) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BetaTool definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(BetaToolUseBlock call, ToolContext context) {
        String path = ToolSchemas.normalizePath(call.input(Input.class).path());
        if (path == null) {
            return ToolResult.error("Invalid path. Use a repository-relative path.");
        }
        List<String> allFiles;
        try {
            allFiles = context.session().repo().listFiles();
        } catch (RuntimeException e) {
            return ToolResult.error("The repository file list is unavailable right now (" + e.getMessage() + ").");
        }
        List<String> related = TestLocator.relatedTests(path, allFiles, MAX_RESULTS);
        List<String> changedTests = context.session().changedPaths().stream()
                .filter(TestLocator::isTestFile)
                .sorted()
                .toList();

        StringBuilder out = new StringBuilder();
        if (related.isEmpty()) {
            out.append("No existing test file matches ").append(path).append(" by naming convention.\n");
        } else {
            out.append("Test files that look related to ").append(path).append(":\n");
            for (String test : related) {
                out.append("- ").append(test);
                if (context.session().changedPaths().contains(test)) {
                    out.append(" (changed in this pull request)");
                }
                out.append('\n');
            }
        }
        if (changedTests.isEmpty()) {
            out.append("This pull request changes no test files.\n");
        } else {
            out.append("Test files changed in this pull request: ").append(String.join(", ", changedTests)).append('\n');
        }
        out.append("Use read_file to check what a test actually covers.");
        return ToolResult.ok(out.toString());
    }
}
