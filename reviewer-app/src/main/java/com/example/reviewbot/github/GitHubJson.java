package com.example.reviewbot.github;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * GitHub's REST API and webhook payloads use snake_case; our records use camelCase. One mapper,
 * shared and thread-safe, translates between them and ignores the many fields we don't need.
 */
public final class GitHubJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .addModule(new JavaTimeModule())
            .build();

    private GitHubJson() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
