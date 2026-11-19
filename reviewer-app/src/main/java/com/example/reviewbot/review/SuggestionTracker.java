package com.example.reviewbot.review;

import com.example.reviewbot.github.GitHubApiException;
import com.example.reviewbot.github.GitHubClient;
import com.example.reviewbot.github.RepoRef;
import com.example.reviewbot.model.Finding;
import com.example.reviewbot.model.ReviewStatus;
import com.example.reviewbot.repository.FindingRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Marks suggestions as accepted when a later commit of the pull request contains the suggested
 * code. This is a heuristic (the author may have written the same fix by hand), which is fine for
 * what it measures: whether the bot's fixes end up in the code.
 */
@Component
public class SuggestionTracker {

    private static final Logger log = LoggerFactory.getLogger(SuggestionTracker.class);
    private static final int MAX_FILES = 20;
    private static final int MIN_SUGGESTION_CHARS = 12;

    private final FindingRepository findings;
    private final GitHubClient gitHub;

    public SuggestionTracker(FindingRepository findings, GitHubClient gitHub) {
        this.findings = findings;
        this.gitHub = gitHub;
    }

    public int markApplied(long installationId, RepoRef repo, int prNumber, String headSha) {
        List<Finding> pending = findings.findPendingSuggestions(repo.fullName(), prNumber, ReviewStatus.COMPLETED);
        if (pending.isEmpty()) {
            return 0;
        }
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        pending.forEach(f -> byFile.computeIfAbsent(f.getFile(), key -> new ArrayList<>()).add(f));

        List<Long> applied = new ArrayList<>();
        int files = 0;
        for (Map.Entry<String, List<Finding>> entry : byFile.entrySet()) {
            if (files++ >= MAX_FILES) {
                break;
            }
            Optional<String> content;
            try {
                content = gitHub.getFileContent(installationId, repo, entry.getKey(), headSha);
            } catch (GitHubApiException e) {
                log.debug("Could not fetch {} to check applied suggestions: {}", entry.getKey(), e.getMessage());
                continue;
            }
            if (content.isEmpty()) {
                continue;
            }
            List<String> fileLines = normalizedLines(content.get());
            for (Finding finding : entry.getValue()) {
                if (isApplied(fileLines, finding.getSuggestion())) {
                    applied.add(finding.getId());
                }
            }
        }
        if (!applied.isEmpty()) {
            findings.markAccepted(applied);
        }
        return applied.size();
    }

    static boolean isApplied(List<String> fileLines, String suggestion) {
        List<String> suggested = normalizedLines(suggestion);
        if (suggested.isEmpty() || String.join("", suggested).length() < MIN_SUGGESTION_CHARS) {
            return false;
        }
        return Collections.indexOfSubList(fileLines, suggested) >= 0;
    }

    static List<String> normalizedLines(String text) {
        if (text == null) {
            return List.of();
        }
        return text.lines()
                .map(line -> line.strip().replaceAll("\\s+", " "))
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
