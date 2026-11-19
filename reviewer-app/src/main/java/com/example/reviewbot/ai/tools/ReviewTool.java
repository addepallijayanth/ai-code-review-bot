package com.example.reviewbot.ai.tools;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolUseBlock;

/** A client-side tool the review agent can call. Implementations are stateless and thread-safe. */
public interface ReviewTool {

    String name();

    BetaTool definition();

    /** Runs one call. Problems are returned as an error result for the model, not thrown. */
    ToolResult execute(BetaToolUseBlock call, ToolContext context);
}
