package com.example.reviewbot.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.RequestTimeoutException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.StructuredMessage;
import com.anthropic.models.beta.messages.StructuredMessageCreateParams;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the Anthropic SDK: applies model routing, enforces the spend budget, records
 * usage and cost for every call, and turns SDK exceptions into retryable / non-retryable
 * {@link AiException}s. All calls go through the beta Messages endpoint because review calls opt
 * into server-side refusal fallback, which is a beta feature.
 */
@Component
public class ClaudeClient {

    private final AnthropicClient client;
    private final ModelRouter router;
    private final ModelPricing pricing;

    public ClaudeClient(AnthropicClient client, ModelRouter router, ModelPricing pricing) {
        this.client = client;
        this.router = router;
        this.pricing = pricing;
    }

    public ModelRouter.Route route(Purpose purpose) {
        return router.route(purpose);
    }

    /**
     * A request builder with the model, adaptive thinking, effort and (where the model supports
     * it) server-side refusal fallback already set for {@code purpose}. {@code maxTokens} must leave
     * room for thinking as well as the visible answer.
     */
    public MessageCreateParams.Builder newRequest(Purpose purpose, long maxTokens) {
        ModelRouter.Route route = router.route(purpose);
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(route.model())
                .maxTokens(maxTokens)
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(route.effort()).build());
        if (route.serverSideFallback()) {
            // On a policy refusal the API re-runs the same request on a fallback model it picks.
            builder.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                    .fallbacks(BetaFallbacksParam.ofDefault());
        }
        return builder;
    }

    public ClaudeResponse send(Purpose purpose, MessageCreateParams params, TokenBudget budget) {
        ensureBudget(budget);
        long started = System.nanoTime();
        BetaMessage message = execute(() -> client.beta().messages().create(params));
        AiUsage usage = usage(purpose, message.model().asString(), message.usage(), started);
        budget.record(usage);
        return new ClaudeResponse(message, usage);
    }

    public <T> StructuredResponse<T> sendStructured(Purpose purpose, StructuredMessageCreateParams<T> params,
                                                    TokenBudget budget) {
        ensureBudget(budget);
        long started = System.nanoTime();
        StructuredMessage<T> message = execute(() -> client.beta().messages().create(params));
        AiUsage usage = usage(purpose, message.model().asString(), message.usage(), started);
        budget.record(usage);
        return StructuredResponse.of(message, usage);
    }

    private static void ensureBudget(TokenBudget budget) {
        if (budget.isExhausted()) {
            throw new BudgetExceededException("Token budget exhausted (" + budget.describe() + ")");
        }
    }

    private AiUsage usage(Purpose purpose, String model, BetaUsage usage, long startedNanos) {
        long cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        BigDecimal cost = pricing.cost(model, usage.inputTokens(), usage.outputTokens(), cacheWrite, cacheRead);
        long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        return new AiUsage(model, purpose, usage.inputTokens(), usage.outputTokens(), cacheRead, cacheWrite, cost, latencyMs);
    }

    private static <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (RateLimitException | InternalServerException | RequestTimeoutException e) {
            throw new AiException("Claude API is unavailable right now (HTTP " + e.statusCode() + ")", true, e);
        } catch (AnthropicServiceException e) {
            throw new AiException("Claude API rejected the request (HTTP " + e.statusCode() + "): " + e.getMessage(), false, e);
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            throw new AiException("Could not reach the Claude API: " + e.getMessage(), true, e);
        }
    }
}
