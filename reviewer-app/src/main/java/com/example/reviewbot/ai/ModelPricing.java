package com.example.reviewbot.ai;

import com.example.reviewbot.config.AiProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Turns token counts into dollars using the price table in {@code reviewbot.ai.pricing}. */
@Component
public class ModelPricing {

    private static final Logger log = LoggerFactory.getLogger(ModelPricing.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, AiProperties.Price> prices;
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    public ModelPricing(AiProperties properties) {
        this.prices = properties.pricing();
    }

    public BigDecimal cost(String model, long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {
        AiProperties.Price price = priceFor(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = perMillion(inputTokens, price.input())
                .add(perMillion(outputTokens, price.output()))
                .add(perMillion(cacheWriteTokens, price.cacheWrite()))
                .add(perMillion(cacheReadTokens, price.cacheRead()));
        long promptTokens = inputTokens + cacheWriteTokens + cacheReadTokens;
        if (price.longContextThreshold() != null && price.longContextMultiplier() != null
                && promptTokens > price.longContextThreshold()) {
            total = total.multiply(price.longContextMultiplier());
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    AiProperties.Price priceFor(String model) {
        if (model != null) {
            AiProperties.Price exact = prices.get(model);
            if (exact != null) {
                return exact;
            }
            // e.g. a suffixed model id: use the longest configured id it starts with
            var prefixed = prices.entrySet().stream()
                    .filter(entry -> model.startsWith(entry.getKey()))
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()));
            if (prefixed.isPresent()) {
                return prefixed.get().getValue();
            }
        }
        // Unknown model: charge the most expensive configured rate so budgets stay conservative.
        if (warnedModels.add(String.valueOf(model))) {
            log.warn("No price configured for model {}; costing it at the highest configured rate", model);
        }
        return prices.values().stream()
                .filter(price -> price.output() != null)
                .max(Comparator.comparing(AiProperties.Price::output))
                .orElse(null);
    }

    private static BigDecimal perMillion(long tokens, BigDecimal pricePerMillion) {
        if (tokens == 0 || pricePerMillion == null) {
            return BigDecimal.ZERO;
        }
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens)).divide(MILLION, 10, RoundingMode.HALF_UP);
    }
}
