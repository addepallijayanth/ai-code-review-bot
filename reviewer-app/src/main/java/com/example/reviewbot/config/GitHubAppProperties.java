package com.example.reviewbot.config;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Identity and endpoints of the GitHub App. The private key and webhook secret are read from the
 * environment (or a mounted secret file) and never live in the repository.
 *
 * @param appId          numeric App ID shown on the App's settings page
 * @param appSlug        URL slug of the App; GitHub posts its comments as {@code <slug>[bot]}
 * @param privateKey     PEM contents of the App's private key (PKCS#1 or PKCS#8, literal "\n" allowed)
 * @param privateKeyPath path to the PEM file, used when {@code privateKey} is empty
 * @param webhookSecret  shared secret used to sign webhook deliveries
 * @param apiUrl         REST API root ({@code https://api.github.com}, or {@code https://HOST/api/v3} on GHES)
 * @param webUrl         web root used to build links in comments
 * @param mention        handle that triggers chat commands, e.g. {@code @reviewbot explain}
 */
@Validated
@ConfigurationProperties(prefix = "reviewbot.github")
public record GitHubAppProperties(
        @NotBlank String appId,
        @DefaultValue("reviewbot") String appSlug,
        String privateKey,
        String privateKeyPath,
        @NotBlank String webhookSecret,
        @DefaultValue("https://api.github.com") URI apiUrl,
        @DefaultValue("https://github.com") String webUrl,
        @DefaultValue("@reviewbot") String mention) {

    /** Login GitHub shows for comments the App posts. */
    public String botLogin() {
        return appSlug + "[bot]";
    }
}
