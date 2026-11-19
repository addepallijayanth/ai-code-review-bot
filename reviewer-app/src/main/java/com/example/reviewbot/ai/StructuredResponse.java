package com.example.reviewbot.ai;

import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.StructuredMessage;
import com.anthropic.models.beta.messages.StructuredTextBlock;
import java.util.Optional;

/**
 * Result of a structured-output call. {@code value} is empty when the model refused, ran out of
 * tokens, or produced output that does not parse; callers decide how to degrade.
 */
public record StructuredResponse<T>(Optional<T> value, boolean refused, AiUsage usage) {

    static <T> StructuredResponse<T> of(StructuredMessage<T> message, AiUsage usage) {
        Optional<BetaStopReason> stopReason = message.stopReason();
        boolean refused = stopReason.map(BetaStopReason.REFUSAL::equals).orElse(false);
        boolean complete = stopReason.map(BetaStopReason.END_TURN::equals).orElse(false);
        Optional<T> value = Optional.empty();
        if (!refused && complete) {
            try {
                value = message.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(StructuredTextBlock::text)
                        .findFirst();
            } catch (RuntimeException e) {
                value = Optional.empty();
            }
        }
        return new StructuredResponse<>(value, refused, usage);
    }
}
