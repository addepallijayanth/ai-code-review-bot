package com.example.reviewbot.github;

import com.example.reviewbot.ai.tools.RepoAccess;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link RepoAccess} backed by the GitHub API at one commit. Results are cached for the lifetime
 * of a review, since several agents (one per file) often read the same files.
 */
public class GitHubRepoAccess implements RepoAccess {

    private final GitHubClient gitHub;
    private final long installationId;
    private final RepoRef repo;
    private final String headSha;
    private final Map<String, Optional<String>> files = new ConcurrentHashMap<>();
    private final ReentrantLock treeLock = new ReentrantLock();
    private volatile List<String> tree;

    public GitHubRepoAccess(GitHubClient gitHub, long installationId, RepoRef repo, String headSha) {
        this.gitHub = gitHub;
        this.installationId = installationId;
        this.repo = repo;
        this.headSha = headSha;
    }

    @Override
    public Optional<String> readFile(String path) {
        Optional<String> cached = files.get(path);
        if (cached != null) {
            return cached;
        }
        // Fetch outside of any map lock: the call blocks on the network.
        Optional<String> content = gitHub.getFileContent(installationId, repo, path, headSha);
        Optional<String> previous = files.putIfAbsent(path, content);
        return previous != null ? previous : content;
    }

    @Override
    public List<String> listFiles() {
        List<String> current = tree;
        if (current != null) {
            return current;
        }
        treeLock.lock();
        try {
            if (tree == null) {
                tree = gitHub.listFilePaths(installationId, repo, headSha);
            }
            return tree;
        } finally {
            treeLock.unlock();
        }
    }

    @Override
    public List<SearchHit> searchCode(String query, int limit) {
        return gitHub.searchCode(installationId, repo, query, limit).stream()
                .map(hit -> new SearchHit(hit.path(), hit.fragments()))
                .toList();
    }
}
