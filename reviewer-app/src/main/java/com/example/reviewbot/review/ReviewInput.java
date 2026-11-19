package com.example.reviewbot.review;

import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.ai.tools.RepoAccess;
import com.example.reviewbot.github.dto.ChangedFile;
import java.util.List;
import java.util.Set;

/**
 * Everything the pipeline needs to review a pull request, independent of where it came from
 * (GitHub in production, files on disk in the evaluation runner).
 *
 * @param previousFingerprints findings already posted on this pull request; they are not repeated
 * @param scope                when not null, only these paths are reviewed (incremental review)
 */
public record ReviewInput(
        PullRequestContext pullRequest,
        List<ChangedFile> files,
        RepoConfig config,
        RepoAccess repo,
        TokenBudget budget,
        Set<String> previousFingerprints,
        Set<String> scope) {
}
