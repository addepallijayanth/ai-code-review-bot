package com.example.reviewbot.github;

import java.util.regex.Pattern;

/** {@code owner/name} of a repository. */
public record RepoRef(String owner, String name) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");

    public RepoRef {
        if (owner == null || !SEGMENT.matcher(owner).matches() || name == null || !SEGMENT.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a repository name: " + owner + "/" + name);
        }
    }

    public static RepoRef parse(String fullName) {
        int slash = fullName == null ? -1 : fullName.indexOf('/');
        if (slash <= 0 || slash != fullName.lastIndexOf('/')) {
            throw new IllegalArgumentException("Expected owner/name but got: " + fullName);
        }
        return new RepoRef(fullName.substring(0, slash), fullName.substring(slash + 1));
    }

    public String fullName() {
        return owner + "/" + name;
    }

    @Override
    public String toString() {
        return fullName();
    }
}
