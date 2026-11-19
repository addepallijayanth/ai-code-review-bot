package com.example.reviewbot.ai.agent;

import com.example.reviewbot.model.ReviewFinding;
import java.util.List;

/** What one agent run produced and why it stopped. Findings reported before a stop are kept. */
public record AgentResult(String path, List<ReviewFinding> findings, int steps, Outcome outcome, String note) {

    public enum Outcome {
        /** The model finished on its own. */
        COMPLETED,
        /** Hit {@code reviewbot.ai.agent.max-steps}. */
        MAX_STEPS,
        /** The pull request's token budget ran out. */
        BUDGET,
        /** The model or its safety classifiers declined to review this file. */
        REFUSED
    }

    public AgentResult {
        findings = List.copyOf(findings);
    }
}
