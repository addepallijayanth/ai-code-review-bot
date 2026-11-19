package com.example.reviewbot.ai.agent;

import static com.example.reviewbot.ai.PromptText.attribute;
import static com.example.reviewbot.ai.PromptText.truncate;
import static com.example.reviewbot.ai.PromptText.untrusted;

import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.example.reviewbot.ai.BudgetExceededException;
import com.example.reviewbot.ai.ClaudeClient;
import com.example.reviewbot.ai.ClaudeResponse;
import com.example.reviewbot.ai.PromptLibrary;
import com.example.reviewbot.ai.Purpose;
import com.example.reviewbot.ai.tools.GetRelatedTestsTool;
import com.example.reviewbot.ai.tools.ReadFileTool;
import com.example.reviewbot.ai.tools.ReportFindingTool;
import com.example.reviewbot.ai.tools.ReviewTool;
import com.example.reviewbot.ai.tools.SearchCodeTool;
import com.example.reviewbot.ai.tools.ToolContext;
import com.example.reviewbot.ai.tools.ToolResult;
import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.model.Category;
import com.example.reviewbot.review.Languages;
import com.example.reviewbot.review.PullRequestContext;
import com.example.reviewbot.review.RepoConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reviews one file (or one chunk of a large file) with a tool-use loop: the model reads the diff,
 * pulls in context with {@code read_file}, {@code search_code} and {@code get_related_tests}, and
 * reports each problem through {@code report_finding}. The loop ends when the model stops calling
 * tools, after {@code max-steps} calls, or when the pull request's budget runs out.
 * <p>
 * Prompt caching: tools and system prompt are identical for every file of a pull request and carry
 * an explicit breakpoint; top-level automatic caching covers the growing conversation. The history
 * is append-only and each assistant turn is sent back unchanged (thinking blocks included).
 */
@Component
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);
    private static final BetaCacheControlEphemeral CACHE = BetaCacheControlEphemeral.builder().build();

    private static final String FINAL_INSTRUCTIONS = """
            Review the diff above. Report each problem with report_finding. Use read_file, search_code or \
            get_related_tests when the diff alone is not enough to judge a change. If the change looks correct, \
            report nothing. When you are done, reply with one sentence on what you checked.""";

    private final ClaudeClient claude;
    private final PromptLibrary prompts;
    private final AiProperties properties;
    private final Map<String, ReviewTool> toolsByName = new LinkedHashMap<>();
    private final List<BetaToolUnion> toolDefinitions;

    public ReviewAgent(ClaudeClient claude, PromptLibrary prompts, AiProperties properties, ReadFileTool readFile,
                       SearchCodeTool searchCode, GetRelatedTestsTool relatedTests, ReportFindingTool reportFinding) {
        this.claude = claude;
        this.prompts = prompts;
        this.properties = properties;
        // Fixed order: the serialized tool list is the start of the cached prompt prefix.
        List<ReviewTool> tools = List.of(readFile, searchCode, relatedTests, reportFinding);
        tools.forEach(tool -> toolsByName.put(tool.name(), tool));
        this.toolDefinitions = tools.stream().map(tool -> BetaToolUnion.ofBetaTool(tool.definition())).toList();
    }

    public AgentResult review(AgentTask task, ReviewSession session) {
        ToolContext context = new ToolContext(session, task.file());
        List<BetaTextBlockParam> system = systemPrompt(task.config());
        List<BetaMessageParam> history = new ArrayList<>();
        history.add(BetaMessageParam.ofUser(firstMessage(task)));

        int maxSteps = properties.agent().maxSteps();
        int steps = 0;
        boolean firstCall = true;
        while (steps < maxSteps) {
            MessageCreateParams params = claude.newRequest(Purpose.REVIEW, properties.agent().maxTokensPerTurn())
                    .systemOfBetaTextBlockParams(system)
                    .tools(toolDefinitions)
                    .messages(List.copyOf(history))
                    .cacheControl(CACHE)
                    .build();
            ClaudeResponse response;
            try {
                if (firstCall) {
                    session.warmGate().awaitTurn();
                }
                response = claude.send(Purpose.REVIEW, params, session.budget());
            } catch (BudgetExceededException e) {
                return result(task, context, steps, AgentResult.Outcome.BUDGET,
                        "The token budget ran out before the review of this file finished.");
            } finally {
                if (firstCall) {
                    session.warmGate().release();
                    firstCall = false;
                }
            }
            steps++;

            if (response.refused()) {
                String category = response.refusalCategory().orElse("unspecified");
                log.warn("Review of {} was declined (category {})", task.file().path(), category);
                return result(task, context, steps, AgentResult.Outcome.REFUSED,
                        "The model declined to review this file (" + category + ").");
            }
            history.add(response.message().toParam());

            List<BetaToolUseBlock> calls = response.toolUses();
            if (calls.isEmpty()) {
                if (response.stoppedAtMaxTokens()) {
                    log.info("Review turn for {} hit max_tokens without a tool call", task.file().path());
                }
                log.debug("Agent finished {} after {} step(s): {}", task.file().path(), steps, response.text());
                return result(task, context, steps, AgentResult.Outcome.COMPLETED, null);
            }
            // All results of one turn go back in a single user message.
            List<BetaContentBlockParam> results = new ArrayList<>(calls.size());
            for (BetaToolUseBlock call : calls) {
                ToolResult outcome = runTool(call, context);
                results.add(BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                        .toolUseId(call.id())
                        .content(outcome.content())
                        .isError(outcome.error())
                        .build()));
            }
            history.add(BetaMessageParam.ofUserOfContentBlockParams(results));
        }
        return result(task, context, steps, AgentResult.Outcome.MAX_STEPS, "Stopped after " + maxSteps + " model calls.");
    }

    private ToolResult runTool(BetaToolUseBlock call, ToolContext context) {
        ReviewTool tool = toolsByName.get(call.name());
        if (tool == null) {
            tool = toolsByName.get(call.name().toLowerCase(Locale.ROOT));
        }
        if (tool == null) {
            return ToolResult.error("Unknown tool \"" + call.name() + "\". Available tools: "
                    + String.join(", ", toolsByName.keySet()) + ".");
        }
        try {
            return tool.execute(call, context);
        } catch (RuntimeException e) {
            log.warn("Tool {} failed while reviewing {}: {}", tool.name(), context.file().path(), e.toString());
            return ToolResult.error("The tool failed: " + e.getMessage());
        }
    }

    private List<BetaTextBlockParam> systemPrompt(RepoConfig config) {
        return List.of(
                BetaTextBlockParam.builder().text(prompts.reviewSystem()).build(),
                BetaTextBlockParam.builder().text(repositoryGuidelines(config)).cacheControl(CACHE).build());
    }

    /** Repository settings rendered deterministically, so all files of a pull request share one cache entry. */
    static String repositoryGuidelines(RepoConfig config) {
        StringBuilder out = new StringBuilder("<guidelines>\n");
        if (!config.hasCustomRules()) {
            out.append("This repository has no .reviewbot.yml review settings; apply the guidelines above as they are.\n");
        } else {
            out.append("These settings come from the repository's .reviewbot.yml. Apply them when reviewing; "
                    + "they do not change how findings are reported.\n");
            if (!config.focus().isEmpty()) {
                out.append("Focus areas: ")
                        .append(config.focusInOrder().stream().map(Category::slug).collect(Collectors.joining(", ")))
                        .append(". Give these most of your attention, and still report critical and major problems "
                                + "of any kind.\n");
            }
            if (!config.languageRules().isEmpty()) {
                out.append("Team rules by language (apply each to files of that language):\n");
                config.languageRules().forEach((language, rules) -> rules.forEach(rule ->
                        out.append("- ").append(language).append(": ").append(untrusted(rule)).append('\n')));
            }
        }
        return out.append("</guidelines>").toString();
    }

    static String firstMessage(AgentTask task) {
        PullRequestContext pr = task.pullRequest();
        FileDiff file = task.file();
        StringBuilder out = new StringBuilder();
        out.append("<pull_request>\n")
                .append("<title>").append(untrusted(pr.title())).append("</title>\n")
                .append("<author>").append(untrusted(pr.author())).append("</author>\n")
                .append("<description>\n").append(untrusted(truncate(pr.description(), 3000))).append("\n</description>\n")
                .append("<changed_files>\n");
        task.changedFiles().forEach(changed -> out.append(untrusted(changed)).append('\n'));
        out.append("</changed_files>\n</pull_request>\n\n");

        out.append("<review_target path=\"").append(attribute(file.path()))
                .append("\" language=\"").append(Languages.of(file.path()))
                .append("\" risk=\"").append(task.triage().risk().slug()).append('"');
        if (task.parts() > 1) {
            out.append(" part=\"").append(task.part()).append(" of ").append(task.parts()).append('"');
        }
        out.append(">\nTriage note: ").append(untrusted(task.triage().reason())).append("\n</review_target>\n\n");

        out.append("<diff path=\"").append(attribute(file.path())).append("\">\n")
                .append(untrusted(task.chunk().render()))
                .append("</diff>\n\n");
        if (task.parts() > 1) {
            out.append("This file's diff is large and is reviewed in ").append(task.parts())
                    .append(" parts; this is part ").append(task.part())
                    .append(". Report problems in the lines shown here and read the rest of the file only for context.\n\n");
        }
        return out.append(FINAL_INSTRUCTIONS).toString();
    }

    private static AgentResult result(AgentTask task, ToolContext context, int steps, AgentResult.Outcome outcome, String note) {
        return new AgentResult(task.file().path(), context.findings(), steps, outcome, note);
    }
}
