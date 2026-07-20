package com.paicli.platform.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WebToolProvider implements ServerToolProvider {
    private final WebAccessService web;
    private final ObjectMapper mapper;

    public WebToolProvider(WebAccessService web, ObjectMapper mapper) {
        this.web = web;
        this.mapper = mapper;
    }

    @Override public String id() { return "web"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        if (!web.enabled()) return List.of();
        return List.of(
                new ModelToolDefinition("web_search", "Search the configured internet search provider",
                        Map.of("type", "object", "properties", Map.of(
                                        "query", Map.of("type", "string"),
                                        "top_k", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                                "required", List.of("query"))),
                new ModelToolDefinition("web_fetch", "Fetch readable text from a public HTTP(S) URL; private network targets are blocked",
                        Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string")),
                                "required", List.of("url"))),
                new ModelToolDefinition("github_repo_fetch", "Fetch GitHub repository metadata, README, and top-level file tree without scraping the HTML page",
                        Map.of("type", "object", "properties", Map.of(
                                        "url", Map.of("type", "string", "description", "GitHub repository URL, for example https://github.com/owner/repo"),
                                        "owner", Map.of("type", "string"),
                                        "repo", Map.of("type", "string"))))
        );
    }

    @Override

    public boolean supports(String toolName) {
        return web.enabled() && ("web_search".equals(toolName) || "web_fetch".equals(toolName)
                || "github_repo_fetch".equals(toolName));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            Object output = switch (request.name()) {
                case "web_search" -> web.search(String.valueOf(request.arguments().getOrDefault("query", "")),
                        integer(request.arguments().get("top_k"), 5));
                case "web_fetch" -> web.fetch(String.valueOf(request.arguments().getOrDefault("url", "")));
                case "github_repo_fetch" -> web.githubRepo(
                        String.valueOf(request.arguments().getOrDefault("url", "")),
                        String.valueOf(request.arguments().getOrDefault("owner", "")),
                        String.valueOf(request.arguments().getOrDefault("repo", "")));
                default -> throw new IllegalArgumentException("unsupported web tool");
            };
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(output), elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
