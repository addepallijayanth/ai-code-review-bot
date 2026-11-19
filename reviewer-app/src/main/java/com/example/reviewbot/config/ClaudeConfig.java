package com.example.reviewbot.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class ClaudeConfig {

    /**
     * The SDK retries 408/409/429/5xx and connection errors with backoff before an exception
     * reaches our code; anything that still fails is classified in {@code ClaudeClient}.
     */
    @Bean
    public AnthropicClient anthropicClient(AiProperties properties) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("reviewbot.ai.api-key is not set (environment variable ANTHROPIC_API_KEY)");
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .maxRetries(properties.maxRetries())
                .timeout(properties.requestTimeout())
                .build();
    }
}
