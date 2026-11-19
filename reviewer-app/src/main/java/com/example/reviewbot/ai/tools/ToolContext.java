package com.example.reviewbot.ai.tools;

import com.example.reviewbot.ai.agent.ReviewSession;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.model.ReviewFinding;
import java.util.ArrayList;
import java.util.List;

/** Per-run state handed to tools: the shared review session, the file under review, and its findings. */
public final class ToolContext {

    private final ReviewSession session;
    private final FileDiff file;
    private final List<ReviewFinding> findings = new ArrayList<>();

    public ToolContext(ReviewSession session, FileDiff file) {
        this.session = session;
        this.file = file;
    }

    public ReviewSession session() {
        return session;
    }

    /** The complete diff of the file under review. */
    public FileDiff file() {
        return file;
    }

    /** Adds a finding and returns its number within this run. */
    public int addFinding(ReviewFinding finding) {
        findings.add(finding);
        return findings.size();
    }

    public int findingCount() {
        return findings.size();
    }

    public List<ReviewFinding> findings() {
        return List.copyOf(findings);
    }
}
