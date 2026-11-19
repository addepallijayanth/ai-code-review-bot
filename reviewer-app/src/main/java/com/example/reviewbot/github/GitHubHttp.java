package com.example.reviewbot.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/** Builds the {@link RestClient} used for every GitHub call. */
final class GitHubHttp {

    static final String API_VERSION = "2022-11-28";
    static final String USER_AGENT = "ai-code-review-bot";

    private GitHubHttp() {
    }

    static RestClient create(URI apiUrl, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(stripTrailingSlash(apiUrl).toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(GitHubJson.mapper()));
                })
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw GitHubApiException.from(request, response);
                })
                .build();
    }

    static URI stripTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? URI.create(value.substring(0, value.length() - 1)) : uri;
    }
}
