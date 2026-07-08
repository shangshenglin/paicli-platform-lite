package com.paicli.platform.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.server.config.PlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolProviderTest {
    @TempDir Path tempDir;

    @Test
    void discoversAndCallsNamespacedRemoteTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of());
                case "tools/list" -> Map.of("tools", java.util.List.of(Map.of(
                        "name", "echo", "description", "echo input", "inputSchema", Map.of(
                                "type", "object", "properties", Map.of("text", Map.of("type", "string"))))));
                case "tools/call" -> Map.of("content", java.util.List.of(Map.of("type", "text", "text",
                        request.path("params").path("arguments").path("text").asText())));
                default -> throw new IllegalStateException(method);
            };
            byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id",
                    request.path("id").asLong(), "result", result));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path config = tempDir.resolve("mcp/servers.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, """
                    {"servers":[{"name":"demo","url":"http://127.0.0.1:%d/mcp","enabled":true}]}
                    """.formatted(server.getAddress().getPort()), StandardCharsets.UTF_8);
            McpToolProvider provider = new McpToolProvider(properties(), mapper);

            assertThat(provider.definitions()).extracting("name").containsExactly("mcp__demo__echo");
            assertThat(provider.requiresApproval("mcp__demo__echo")).isTrue();
            var result = provider.execute(new ToolRequest("tool-1", "run-1", "mcp__demo__echo",
                    Map.of("text", "hello"), "key"));
            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("hello");
        } finally {
            server.stop(0);
        }
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
