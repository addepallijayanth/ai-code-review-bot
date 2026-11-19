package com.example.reviewbot.webhook;

import com.example.reviewbot.config.SecurityConfig;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives GitHub App webhooks. The signature is checked against the raw bytes before anything
 * is parsed; slow work is queued and the endpoint answers right away (202 when a job was queued).
 */
@RestController
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final SignatureVerifier verifier;
    private final WebhookEventHandler handler;

    public GitHubWebhookController(SignatureVerifier verifier, WebhookEventHandler handler) {
        this.verifier = verifier;
        this.handler = handler;
    }

    @PostMapping(SecurityConfig.WEBHOOK_PATH)
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(name = "X-GitHub-Event", required = false) String event,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        if (!verifier.isValid(payload, signature)) {
            log.warn("Rejected webhook delivery {} ({}): invalid or missing signature", deliveryId, event);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature"));
        }
        if (event == null || deliveryId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing X-GitHub-Event or X-GitHub-Delivery header"));
        }
        MDC.put("delivery", deliveryId);
        try {
            WebhookOutcome outcome = handler.handle(event, deliveryId, payload);
            log.info("Webhook {} {}: {}", event, outcome.status().name().toLowerCase(Locale.ROOT), outcome.detail());
            HttpStatus status = outcome.status() == WebhookOutcome.Status.QUEUED ? HttpStatus.ACCEPTED : HttpStatus.OK;
            return ResponseEntity.status(status).body(Map.of(
                    "status", outcome.status().name().toLowerCase(Locale.ROOT),
                    "detail", outcome.detail()));
        } catch (IOException e) {
            log.warn("Malformed {} payload in delivery {}: {}", event, deliveryId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "malformed payload"));
        } finally {
            MDC.remove("delivery");
        }
    }
}
