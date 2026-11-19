package com.example.reviewbot.ai;

import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaRefusalStopDetails;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** A model response plus the usage it was billed for. Content blocks are read by type, never by position. */
public record ClaudeResponse(BetaMessage message, AiUsage usage) {

    /** Safety classifiers (or the model) declined; content is empty or partial and must not be used. */
    public boolean refused() {
        return message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false);
    }

    public Optional<String> refusalCategory() {
        return message.stopDetails().flatMap(BetaRefusalStopDetails::category).map(Object::toString);
    }

    public boolean stoppedAtMaxTokens() {
        return message.stopReason().map(BetaStopReason.MAX_TOKENS::equals).orElse(false);
    }

    public List<BetaToolUseBlock> toolUses() {
        return message.content().stream().flatMap(block -> block.toolUse().stream()).toList();
    }

    public String text() {
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(BetaTextBlock::text)
                .collect(Collectors.joining("\n"))
                .strip();
    }
}
