package com.example.reviewbot.ai.tools;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of the repository at the pull request's head commit, as seen by the agent's
 * tools. Production uses GitHub; the evaluation runner uses files on disk.
 */
public interface RepoAccess {

    /** File contents, or empty if the path does not exist at the head commit. */
    Optional<String> readFile(String path);

    /** Every file path at the head commit; empty when the listing is unavailable. */
    List<String> listFiles();

    /** Code search; may lag behind the pull request (GitHub indexes the default branch). */
    List<SearchHit> searchCode(String query, int limit);

    record SearchHit(String path, List<String> fragments) {
    }
}
