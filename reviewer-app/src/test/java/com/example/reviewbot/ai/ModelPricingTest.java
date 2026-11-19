package com.example.reviewbot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.support.TestProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ModelPricingTest {

    private final AiProperties properties = TestProperties.ai();
    private final ModelPricing pricing = new ModelPricing(properties);

    @Test
    void applicationYmlDefinesPricesAndModels() {
        assertThat(properties.pricing()).containsKeys("claude-sonnet-5-5", "claude-sonnet-5", "claude-haiku-5-5");
        assertThat(properties.models().triage()).isEqualTo("claude-haiku-5-5");
        assertThat(properties.models().review()).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void pricesSonnetTokensIncludingCacheReadsAndWrites() {
        // 10k uncached input at $2, 2k output at $10, 3k cache write at $2.50, 50k cache read at $0.10
        BigDecimal cost = pricing.cost("claude-sonnet-5-5", 10_000, 2_000, 3_000, 50_000);

        assertThat(cost).isEqualByComparingTo("0.0525");
    }

    @Test
    void appliesHaikusLongContextRateAbove100kPromptTokens() {
        BigDecimal shortPrompt = pricing.cost("claude-haiku-5-5", 100_000, 1_000, 0, 0);
        BigDecimal longPrompt = pricing.cost("claude-haiku-5-5", 100_001, 1_000, 0, 0);

        assertThat(shortPrompt).isEqualByComparingTo("0.0105");
        assertThat(longPrompt).isGreaterThan(shortPrompt.multiply(BigDecimal.valueOf(4)));
    }

    @Test
    void costsUnknownModelsAtTheHighestConfiguredRate() {
        assertThat(pricing.cost("claude-mystery-9", 1_000_000, 0, 0, 0)).isEqualByComparingTo("2.00");
        assertThat(pricing.cost("claude-sonnet-5-5-20990101", 1_000_000, 0, 0, 0)).isEqualByComparingTo("2.00");
    }

    @Test
    void routesTriageToHaikuWithoutServerFallbackAndReviewToSonnetWithIt() {
        ModelRouter router = new ModelRouter(properties);

        ModelRouter.Route triage = router.route(Purpose.TRIAGE);
        ModelRouter.Route review = router.route(Purpose.REVIEW);

        assertThat(triage.model()).isEqualTo("claude-haiku-5-5");
        assertThat(triage.effort()).isEqualTo(BetaOutputConfig.Effort.LOW);
        assertThat(triage.serverSideFallback()).isFalse();
        assertThat(review.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(review.effort()).isEqualTo(BetaOutputConfig.Effort.MEDIUM);
        assertThat(review.serverSideFallback()).isTrue();
    }

    @Test
    void budgetCountsBillableTokensAndCost() {
        TokenBudget budget = new TokenBudget(1_000, new BigDecimal("1.00"));
        budget.record(new AiUsage("m", Purpose.REVIEW, 400, 100, 90_000, 0, new BigDecimal("0.10"), 1));
        assertThat(budget.isExhausted()).isFalse();

        budget.record(new AiUsage("m", Purpose.REVIEW, 400, 200, 0, 0, new BigDecimal("0.10"), 1));
        assertThat(budget.isExhausted()).isTrue();
        assertThat(budget.tokensUsed()).isEqualTo(1_100);
    }
}
