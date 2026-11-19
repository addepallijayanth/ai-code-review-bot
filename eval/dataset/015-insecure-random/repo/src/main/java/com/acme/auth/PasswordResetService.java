package com.acme.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Random;

public class PasswordResetService {

    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    private final ResetTokenRepository tokens;
    private final Mailer mailer;
    private final Clock clock;

    public PasswordResetService(ResetTokenRepository tokens, Mailer mailer, Clock clock) {
        this.tokens = tokens;
        this.mailer = mailer;
        this.clock = clock;
    }

    public void startReset(User user) {
        String token = Long.toHexString(new Random().nextLong());
        tokens.save(new ResetToken(user.getId(), token, clock.instant().plus(TOKEN_LIFETIME)));
        mailer.send(user.getEmail(), "Reset your password", "https://app.acme.test/reset?token=" + token);
    }
}
