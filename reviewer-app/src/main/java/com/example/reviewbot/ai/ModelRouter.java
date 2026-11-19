package com.example.reviewbot.ai;

import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.example.reviewbot.config.AiProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Picks the model and effort for each kind of call: a fast, cheap model decides which files are
 * worth a careful look; a stronger model does the review and answers chat commands.
 */
@Component
public class ModelRouter {

    /** Models that accept the server-side {@code fallbacks} parameter. */
    private static final List<String> SERVER_FALLBACK_MODELS = List.of("claude-sonnet-5-5", "claude-opus-5", "claude-fable-5");

    private final AiProperties properties;

    public ModelRouter(AiProperties properties) {
        this.properties = properties;
    }

    public Route route(Purpose purpose) {
        AiProperties.Models models = properties.models();
        AiProperties.Agent agent = properties.agent();
        return switch (purpose) {
            case TRIAGE -> route(purpose, models.triage(), agent.triageEffort());
            case REVIEW -> route(purpose, models.review(), agent.reviewEffort());
            case EXPLAIN -> route(purpose, models.chat(), agent.explainEffort());
            case FIX -> route(purpose, models.chat(), agent.fixEffort());
        };
    }

    private Route route(Purpose purpose, String model, String effort) {
        boolean fallback = properties.refusalFallback()
                && SERVER_FALLBACK_MODELS.stream().anyMatch(model::startsWith);
        return new Route(purpose, model, BetaOutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT)), fallback);
    }

    /**
     * @param serverSideFallback whether to ask the API to retry a policy refusal on another model
     */
    public record Route(Purpose purpose, String model, BetaOutputConfig.Effort effort, boolean serverSideFallback) {
    }
}
