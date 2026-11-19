package com.example.reviewbot.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The three tools are registered with schemas the MCP server advertises. The stdio transport stays off in tests. */
@SpringBootTest(properties = {"spring.ai.mcp.server.enabled=false", "reviewbot.mcp.github.token="})
class McpServerApplicationTest {

    @Autowired
    ToolCallbackProvider tools;

    @Test
    void registersTheGitHubTools() {
        ToolCallback[] callbacks = tools.getToolCallbacks();

        assertThat(Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder("read_file", "search_code", "list_prs");
        assertThat(Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals("read_file")).findFirst().orElseThrow()
                .getToolDefinition().inputSchema()).contains("\"repository\"").contains("\"path\"").contains("\"ref\"");
    }
}
