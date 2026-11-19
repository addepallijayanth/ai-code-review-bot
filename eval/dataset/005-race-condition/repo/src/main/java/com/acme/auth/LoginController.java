package com.acme.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

    private final LoginAttemptTracker attempts;
    private final Authenticator authenticator;

    public LoginController(LoginAttemptTracker attempts, Authenticator authenticator) {
        this.attempts = attempts;
        this.authenticator = authenticator;
    }

    @PostMapping("/login")
    public ResponseEntity<Session> login(@RequestBody Credentials credentials) {
        if (attempts.isLocked(credentials.username())) {
            return ResponseEntity.status(HttpStatus.LOCKED).build();
        }
        return authenticator.authenticate(credentials)
                .map(session -> {
                    attempts.recordSuccess(credentials.username());
                    return ResponseEntity.ok(session);
                })
                .orElseGet(() -> {
                    attempts.recordFailure(credentials.username());
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                });
    }
}
