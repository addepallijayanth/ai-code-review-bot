package com.acme.auth;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Counts failed logins since the last successful one. One instance serves all requests. */
@Component
public class LoginAttemptTracker {

    private static final int MAX_ATTEMPTS = 5;

    private final Map<String, Integer> failures = new HashMap<>();

    public void recordFailure(String username) {
        int count = failures.getOrDefault(username, 0);
        failures.put(username, count + 1);
    }

    public boolean isLocked(String username) {
        return failures.getOrDefault(username, 0) >= MAX_ATTEMPTS;
    }

    public void recordSuccess(String username) {
        failures.remove(username);
    }
}
