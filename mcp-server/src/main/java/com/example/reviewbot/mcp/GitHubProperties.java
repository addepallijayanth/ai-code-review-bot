package com.example.reviewbot.mcp;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param token  a fine-grained personal access token with read access to the repositories
 *               (Contents and Pull requests: read-only); optional for public repositories
 * @param apiUrl REST API root
 */
@ConfigurationProperties(prefix = "reviewbot.mcp.github")
public record GitHubProperties(String token, @DefaultValue("https://api.github.com") URI apiUrl) {
}
