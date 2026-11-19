package com.example.reviewbot.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubToolsTest {

    private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

    private GitHubTools tools;

    @BeforeAll
    static void start() {
        GITHUB.start();
    }

    @AfterAll
    static void stop() {
        GITHUB.stop();
    }

    @BeforeEach
    void setUp() {
        GITHUB.resetAll();
        tools = new GitHubTools(new GitHubProperties("ghp_test", URI.create(GITHUB.baseUrl())));
    }

    @Test
    void readsAFileWithLineNumbersAtARef() {
        GITHUB.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/src/App.java"))
                .withQueryParam("ref", equalTo("main"))
                .willReturn(aResponse().withBody("class App {\n}\n")));

        assertThat(tools.readFile("octo/shop", "src/App.java", "main")).isEqualTo("     1| class App {\n     2| }\n");
        GITHUB.verify(getRequestedFor(urlPathEqualTo("/repos/octo/shop/contents/src/App.java"))
                .withHeader("Authorization", equalTo("Bearer ghp_test"))
                .withHeader("Accept", equalTo("application/vnd.github.raw+json")));
    }

    @Test
    void explainsAMissingFileInsteadOfFailing() {
        GITHUB.stubFor(get(urlPathEqualTo("/repos/octo/shop/contents/nope.txt")).willReturn(aResponse().withStatus(404)));

        assertThat(tools.readFile("octo/shop", "nope.txt", null)).isEqualTo("No file at nope.txt in octo/shop.");
    }

    @Test
    void searchesCodeWithinTheRepository() {
        GITHUB.stubFor(get(urlPathEqualTo("/search/code"))
                .withQueryParam("q", equalTo("calculateTotal repo:octo/shop"))
                .willReturn(okJson("""
                        {"items":[{"path":"src/Cart.java","text_matches":[{"fragment":"int t = calculateTotal(items);"}]}]}
                        """)));

        assertThat(tools.searchCode("octo/shop", "calculateTotal"))
                .isEqualTo("src/Cart.java\n    int t = calculateTotal(items);");
    }

    @Test
    void listsPullRequestsOnePerLine() {
        GITHUB.stubFor(get(urlPathEqualTo("/repos/octo/shop/pulls")).withQueryParam("state", equalTo("open")).willReturn(okJson("""
                [{"number":12,"title":"Add cart totals","draft":false,"user":{"login":"alice"},
                  "head":{"ref":"feature/totals"},"base":{"ref":"main"},"updated_at":"2026-11-18T09:00:00Z"}]
                """)));

        assertThat(tools.listPullRequests("octo/shop", null))
                .isEqualTo("#12 Add cart totals (alice, feature/totals -> main, updated 2026-11-18T09:00:00Z)");
    }

    @Test
    void rejectsMalformedRepositoryNames() {
        assertThatThrownBy(() -> tools.listPullRequests("not a repo", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tools.listPullRequests("octo/shop", "merged")).isEqualTo("state must be open, closed or all.");
    }
}
