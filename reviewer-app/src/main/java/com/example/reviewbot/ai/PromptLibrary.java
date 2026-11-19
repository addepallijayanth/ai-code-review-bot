package com.example.reviewbot.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * System prompts, loaded once from {@code classpath:prompts/}. They are kept byte-for-byte stable
 * (line endings normalised, nothing interpolated) because they form the cached prompt prefix.
 */
@Component
public class PromptLibrary {

    private static final List<String> NAMES = List.of("review-system", "triage", "explain-finding", "fix-finding");

    private final Map<String, String> prompts = new HashMap<>();

    public PromptLibrary() {
        NAMES.forEach(name -> prompts.put(name, load(name)));
    }

    public String reviewSystem() {
        return prompts.get("review-system");
    }

    public String triage() {
        return prompts.get("triage");
    }

    public String explainFinding() {
        return prompts.get("explain-finding");
    }

    public String fixFinding() {
        return prompts.get("fix-finding");
    }

    private static String load(String name) {
        ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompt " + resource.getPath(), e);
        }
    }
}
