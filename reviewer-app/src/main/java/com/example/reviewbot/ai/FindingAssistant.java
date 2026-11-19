package com.example.reviewbot.ai;

import static com.example.reviewbot.ai.PromptText.attribute;
import static com.example.reviewbot.ai.PromptText.truncate;
import static com.example.reviewbot.ai.PromptText.untrusted;

import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.StructuredMessageCreateParams;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Answers {@code @reviewbot explain} and {@code @reviewbot fix} on a finding with one short model call. */
@Service
public class FindingAssistant {

    private static final long EXPLAIN_MAX_TOKENS = 6000;
    private static final long FIX_MAX_TOKENS = 12000;

    private final ClaudeClient claude;
    private final PromptLibrary prompts;

    public FindingAssistant(ClaudeClient claude, PromptLibrary prompts) {
        this.claude = claude;
        this.prompts = prompts;
    }

    /** A Markdown explanation, or empty when the model declined or said nothing. */
    public Optional<String> explain(FindingContext finding, TokenBudget budget) {
        MessageCreateParams params = claude.newRequest(Purpose.EXPLAIN, EXPLAIN_MAX_TOKENS)
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(prompts.explainFinding()).build()))
                .addUserMessage(render(finding, false))
                .build();
        ClaudeResponse response = claude.send(Purpose.EXPLAIN, params, budget);
        if (response.refused()) {
            return Optional.empty();
        }
        String text = response.text();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    public Optional<FixProposal> fix(FindingContext finding, TokenBudget budget) {
        ModelRouter.Route route = claude.route(Purpose.FIX);
        StructuredMessageCreateParams<FixProposal> params = claude.newRequest(Purpose.FIX, FIX_MAX_TOKENS)
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(prompts.fixFinding()).build()))
                .addUserMessage(render(finding, true))
                .outputConfig(FixProposal.class, route.effort())
                .build();
        return claude.sendStructured(Purpose.FIX, params, budget).value();
    }

    String render(FindingContext f, boolean includeTarget) {
        String location = f.startLine() != null
                ? "lines " + f.startLine() + "-" + f.line()
                : "line " + f.line();
        StringBuilder out = new StringBuilder();
        out.append("<finding>\n")
                .append("Severity: ").append(f.severity().label()).append('\n')
                .append("Category: ").append(f.category().label()).append('\n')
                .append("Title: ").append(untrusted(f.title())).append('\n')
                .append("Location: ").append(untrusted(f.file())).append(", ").append(location).append('\n')
                .append("What the review said:\n").append(untrusted(f.message())).append('\n');
        if (f.suggestion() != null && !f.suggestion().isBlank()) {
            out.append("Replacement the review suggested:\n").append(untrusted(f.suggestion())).append('\n');
        }
        out.append("</finding>\n\n")
                .append("<code path=\"").append(attribute(f.file())).append("\">\n")
                .append(untrusted(f.codeExcerpt())).append("\n</code>\n\n");
        if (includeTarget) {
            out.append("<target_lines path=\"").append(attribute(f.file()))
                    .append("\" start=\"").append(f.firstLine())
                    .append("\" end=\"").append(f.line()).append("\">\n")
                    .append(untrusted(f.targetLines())).append("\n</target_lines>\n\n");
        }
        out.append("<question>\n").append(untrusted(truncate(f.question(), 2000))).append("\n</question>\n");
        return out.toString();
    }
}
