package com.example.reviewbot.ai.agent;

import com.example.reviewbot.ai.TriageVerdict;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.review.PullRequestContext;
import com.example.reviewbot.review.RepoConfig;
import java.util.List;

/**
 * One agent run: review {@code chunk} (all or part of {@code file}'s diff).
 *
 * @param file         the whole diff of the file; findings may be reported on any of its lines
 * @param chunk        the part of the diff shown to the model in this run
 * @param changedFiles one-line summaries of every file in the pull request, for context
 */
public record AgentTask(
        PullRequestContext pullRequest,
        FileDiff file,
        FileDiff chunk,
        int part,
        int parts,
        TriageVerdict triage,
        RepoConfig config,
        List<String> changedFiles) {
}
