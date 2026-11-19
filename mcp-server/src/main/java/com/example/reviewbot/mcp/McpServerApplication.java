package com.example.reviewbot.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Exposes the reviewer's GitHub tools over the Model Context Protocol (stdio), so Claude Code or
 * Claude Desktop can read files, search code and list pull requests in the same way the review
 * agent does.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    ToolCallbackProvider gitHubToolCallbacks(GitHubTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
