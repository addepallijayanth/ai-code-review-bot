package com.example.reviewbot.ai.tools;

import static com.example.reviewbot.ai.PromptText.attribute;
import static com.example.reviewbot.ai.PromptText.truncate;
import static com.example.reviewbot.ai.PromptText.untrusted;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@code search_code(query)}: find usages of a changed method, class, or constant. */
@Component
public class SearchCodeTool implements ReviewTool {

    public static final String NAME = "search_code";
    private static final int MAX_RESULTS = 8;
    private static final int MAX_FRAGMENT_CHARS = 400;

    private static final BetaTool DEFINITION = ToolSchemas.strictTool(NAME,
            "Search the repository for an identifier or a short phrase, for example a changed method's name to find "
                    + "its callers. Results come from GitHub code search, which indexes the default branch, so code "
                    + "added in this pull request may be missing. Returns matching file paths with short fragments; "
                    + "use read_file to see more of a match.",
            List.of("query"),
            ToolSchemas.string("query", "An identifier or short exact phrase, e.g. calculateDiscount or \"new UserDao(\""));

    record Input(String query) {
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
        String query = call.input(Input.class).query();
        if (query == null || query.isBlank() || query.length() > 200) {
            return ToolResult.error("The query must be a non-empty identifier or phrase of at most 200 characters.");
        }
        query = query.strip();
        if (!context.session().tryUseSearch()) {
            return ToolResult.error("The code-search allowance for this review is used up. Use read_file on specific files instead.");
        }
        List<RepoAccess.SearchHit> hits;
        try {
            hits = context.session().repo().searchCode(query, MAX_RESULTS);
        } catch (RuntimeException e) {
            return ToolResult.error("Code search is unavailable right now (" + e.getMessage() + "). Continue without it.");
        }
        if (hits.isEmpty()) {
            return ToolResult.ok("No results for \"" + query + "\". Code search only covers the default branch.");
        }
        StringBuilder out = new StringBuilder("<search_results query=\"").append(attribute(query)).append("\">\n");
        for (RepoAccess.SearchHit hit : hits) {
            out.append("<result path=\"").append(attribute(hit.path())).append("\">\n");
            for (String fragment : hit.fragments()) {
                String redacted = context.session().redactor().redact(fragment);
                out.append(untrusted(truncate(redacted, MAX_FRAGMENT_CHARS))).append("\n---\n");
            }
            out.append("</result>\n");
        }
        out.append("</search_results>");
        return ToolResult.ok(out.toString());
    }
}
