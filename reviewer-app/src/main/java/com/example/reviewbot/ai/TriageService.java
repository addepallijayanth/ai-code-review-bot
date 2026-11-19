package com.example.reviewbot.ai;

import static com.example.reviewbot.ai.PromptText.attribute;
import static com.example.reviewbot.ai.PromptText.truncate;
import static com.example.reviewbot.ai.PromptText.untrusted;

import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.StructuredMessageCreateParams;
import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.review.Languages;
import com.example.reviewbot.review.PullRequestContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * First pass with the fast model: for each changed file, is a careful review worth it? Trivial
 * changes (renames, comments, formatting, version bumps) are skipped, which is where most of the
 * cost saving comes from. Files are triaged in batches with structured output.
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);
    private static final long MAX_TOKENS = 8000;

    private final ClaudeClient claude;
    private final PromptLibrary prompts;
    private final AiProperties properties;

    public TriageService(ClaudeClient claude, PromptLibrary prompts, AiProperties properties) {
        this.claude = claude;
        this.prompts = prompts;
        this.properties = properties;
    }

    /** A verdict for every file in {@code files}; files triage could not decide on are reviewed. */
    public Map<String, TriageVerdict> triage(PullRequestContext pr, List<FileDiff> files, TokenBudget budget) {
        Map<String, TriageVerdict> verdicts = new LinkedHashMap<>();
        int batchSize = Math.max(1, properties.agent().triageBatchSize());
        for (int from = 0; from < files.size(); from += batchSize) {
            List<FileDiff> batch = files.subList(from, Math.min(files.size(), from + batchSize));
            try {
                verdicts.putAll(triageBatch(pr, batch, budget));
            } catch (AiException e) {
                log.warn("Triage failed for {} file(s); reviewing all of them: {}", batch.size(), e.getMessage());
            }
        }
        for (FileDiff file : files) {
            verdicts.putIfAbsent(file.path(), TriageVerdict.failOpen(file.path()));
        }
        return verdicts;
    }

    private Map<String, TriageVerdict> triageBatch(PullRequestContext pr, List<FileDiff> batch, TokenBudget budget) {
        ModelRouter.Route route = claude.route(Purpose.TRIAGE);
        StructuredMessageCreateParams<TriageOutput> params = claude.newRequest(Purpose.TRIAGE, MAX_TOKENS)
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(prompts.triage()).build()))
                .addUserMessage(render(pr, batch))
                .outputConfig(TriageOutput.class, route.effort())
                .build();
        StructuredResponse<TriageOutput> response = claude.sendStructured(Purpose.TRIAGE, params, budget);
        if (response.value().isEmpty()) {
            log.warn("Triage returned no usable result (refused={}); reviewing the batch", response.refused());
            return Map.of();
        }
        Set<String> requested = batch.stream().map(FileDiff::path).collect(Collectors.toSet());
        Map<String, TriageVerdict> verdicts = new LinkedHashMap<>();
        List<TriageOutput.FileTriage> decisions = response.value().get().files();
        if (decisions == null) {
            return verdicts;
        }
        for (TriageOutput.FileTriage decision : decisions) {
            String path = decision.path() == null ? "" : decision.path().strip();
            if (requested.contains(path)) {
                String reason = decision.reason() == null ? "" : decision.reason().strip();
                verdicts.put(path, new TriageVerdict(path, decision.needsDeepReview(), Risk.parse(decision.risk()), reason));
            }
        }
        return verdicts;
    }

    String render(PullRequestContext pr, List<FileDiff> batch) {
        StringBuilder out = new StringBuilder();
        out.append("<pull_request>\n<title>").append(untrusted(pr.title())).append("</title>\n")
                .append("<description>\n").append(untrusted(truncate(pr.description(), 1500))).append("\n</description>\n")
                .append("</pull_request>\n\n")
                .append("Triage each of the ").append(batch.size()).append(" files below.\n\n");
        int maxLines = properties.agent().triageMaxLinesPerFile();
        for (FileDiff file : batch) {
            out.append("<diff path=\"").append(attribute(file.path()))
                    .append("\" language=\"").append(Languages.of(file.path()))
                    .append("\" added=\"").append(file.addedCount())
                    .append("\" removed=\"").append(file.removedCount()).append("\">\n")
                    .append(untrusted(firstLines(file.render(), maxLines)))
                    .append("</diff>\n\n");
        }
        return out.toString();
    }

    private static String firstLines(String text, int maxLines) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) {
            return text;
        }
        return String.join("\n", List.of(lines).subList(0, maxLines))
                + "\n[" + (lines.length - maxLines) + " more lines not shown]\n";
    }
}
