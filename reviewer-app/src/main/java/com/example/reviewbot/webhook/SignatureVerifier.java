package com.example.reviewbot.webhook;

import com.example.reviewbot.config.GitHubAppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies {@code X-Hub-Signature-256}: HMAC-SHA256 of the raw request body keyed with the
 * webhook secret, hex-encoded with a {@code sha256=} prefix. Compared in constant time.
 */
@Component
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SignatureVerifier(GitHubAppProperties properties) {
        this.key = new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public boolean isValid(byte[] payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(payload), provided);
    }

    /** The header value GitHub would send for {@code payload}. */
    public String sign(byte[] payload) {
        return PREFIX + HexFormat.of().formatHex(hmac(payload));
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
