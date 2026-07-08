package com.paicli.platform.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Component
public class McpToolProvider implements ServerToolProvider {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_.-]{1,80}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int MAX_ARGUMENT_BYTES = 256_000;
    private static final int MAX_SCHEMA_BYTES = 64_000;
    private final Path configPath;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, ServerState> states = new ConcurrentHashMap<>();
    private volatile Map<String, RemoteTool> tools = Map.of();
    private volatile Instant refreshedAt = Instant.EPOCH;

    public McpToolProvider(PlatformProperties properties, ObjectMapper mapper) {
        this.configPath = properties.dataDir().toAbsolutePath().normalize().resolve("mcp/servers.json");
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String id() { return "mcp"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        refreshIfNeeded();
        return tools.values().stream().sorted(Comparator.comparing(RemoteTool::namespacedName))
                .map(tool -> new ModelToolDefinition(tool.namespacedName(), tool.description(), tool.inputSchema()))
                .toList();
    }

    @Override
    public boolean supports(String toolName) {
        if (toolName == null || !toolName.startsWith("mcp__")) return false;
        refreshIfNeeded();
        return tools.containsKey(toolName);
    }

    @Override public boolean requiresApproval(String toolName) { return supports(toolName); }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            refreshIfNeeded();
            RemoteTool tool = tools.get(request.name());
            if (tool == null) throw new IllegalArgumentException("MCP tool is unavailable: " + request.name());
            ServerConfig config = configurations().stream().filter(server -> server.name().equals(tool.server()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("MCP server config disappeared"));
            ServerState state = states.computeIfAbsent(config.name(), ignored -> new ServerState());
            if (state.circuitOpen()) throw new IllegalStateException("MCP server circuit is temporarily open");
            if (mapper.writeValueAsBytes(request.arguments()).length > MAX_ARGUMENT_BYTES) {
                throw new IllegalArgumentException("MCP tool arguments exceed size budget");
            }
            JsonNode result = rpc(config, state, "tools/call", Map.of(
                    "name", tool.remoteName(), "arguments", request.arguments()), true);
            state.succeeded();
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(result), elapsed(start));
        } catch (Exception e) {
            RemoteTool tool = tools.get(request.name());
            if (tool != null) states.computeIfAbsent(tool.server(), ignored -> new ServerState()).failed(message(e));
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    public List<ServerStatus> statuses() {
        refreshIfNeeded();
        List<ServerStatus> values = new ArrayList<>();
        for (ServerConfig config : configurations()) {
            ServerState state = states.get(config.name());
            values.add(new ServerStatus(config.name(), config.enabled(), state != null && state.ready,
                    state == null ? "" : state.error));
        }
        return values;
    }

    private synchronized void refreshIfNeeded() {
        if (Instant.now().isBefore(refreshedAt.plus(CACHE_TTL))) return;
        Map<String, RemoteTool> discovered = new LinkedHashMap<>();
        for (ServerConfig config : configurations()) {
            if (!config.enabled()) continue;
            ServerState state = states.computeIfAbsent(config.name(), ignored -> new ServerState());
            if (state.circuitOpen()) {
                state.ready = false;
                state.error = "circuit open until " + state.openUntil;
                continue;
            }
            try {
                ensureInitialized(config, state);
                JsonNode result = rpc(config, state, "tools/list", Map.of(), true);
                for (JsonNode node : result.path("tools")) {
                    String remoteName = node.path("name").asText("").trim();
                    if (remoteName.isBlank()) continue;
                    String safeTool = remoteName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                    String namespaced = "mcp__" + config.name() + "__" + safeTool;
                    Map<String, Object> schema = safeSchema(node.get("inputSchema"));
                    discovered.put(namespaced, new RemoteTool(config.name(), remoteName, namespaced,
                            node.path("description").asText("MCP tool " + remoteName), schema));
                }
                state.ready = true;
                state.succeeded();
            } catch (Exception e) {
                state.ready = false;
                state.failed(message(e));
            }
        }
        tools = Map.copyOf(discovered);
        refreshedAt = Instant.now();
    }

    private Map<String, Object> safeSchema(JsonNode node) {
        try {
            if (node == null || !node.isObject() || mapper.writeValueAsBytes(node).length > MAX_SCHEMA_BYTES) {
                return Map.of("type", "object", "properties", Map.of());
            }
            Map<String, Object> schema = mapper.convertValue(node, MAP);
            if (!"object".equals(String.valueOf(schema.getOrDefault("type", "object")))) {
                return Map.of("type", "object", "properties", Map.of());
            }
            return schema;
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private void ensureInitialized(ServerConfig config, ServerState state) throws Exception {
        if (state.initialized) return;
        rpc(config, state, "initialize", Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "paicli-platform-lite", "version", "0.7")), true);
        rpc(config, state, "notifications/initialized", Map.of(), false);
        state.initialized = true;
    }

    private JsonNode rpc(ServerConfig config, ServerState state, String method,
                         Map<String, Object> params, boolean expectsResponse) throws Exception {
        long id = requestIds.incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        if (expectsResponse) payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)));
        resolvedHeaders(config).forEach(builder::header);
        if (!state.sessionId.isBlank()) builder.header("Mcp-Session-Id", state.sessionId);
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        state.sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(state.sessionId);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("MCP server returned HTTP " + response.statusCode());
        }
        byte[] bytes;
        try (InputStream input = response.body()) { bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1); }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("MCP response is too large");
        if (!expectsResponse || bytes.length == 0) return mapper.createObjectNode();
        String body = new String(bytes, StandardCharsets.UTF_8);
        JsonNode envelope = parseEnvelope(body, response.headers().firstValue("content-type").orElse(""), id);
        if (envelope.has("error")) throw new IllegalStateException("MCP error: " + envelope.get("error"));
        return envelope.path("result");
    }

    private JsonNode parseEnvelope(String body, String contentType, long id) throws Exception {
        if (!contentType.toLowerCase().contains("text/event-stream")) return mapper.readTree(body);
        for (String line : body.split("\\R")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isBlank()) continue;
            JsonNode node = mapper.readTree(data);
            if (node.path("id").asLong(Long.MIN_VALUE) == id) return node;
        }
        throw new IllegalStateException("MCP event stream contained no matching response");
    }

    private List<ServerConfig> configurations() {
        if (!Files.isRegularFile(configPath)) return List.of();
        try {
            JsonNode root = mapper.readTree(Files.readString(configPath, StandardCharsets.UTF_8));
            List<ServerConfig> values = new ArrayList<>();
            for (JsonNode node : root.path("servers")) {
                String name = node.path("name").asText("").trim();
                String url = node.path("url").asText("").trim();
                if (!NAME.matcher(name).matches() || url.isBlank()) continue;
                URI uri = URI.create(url);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) continue;
                Map<String, String> headers = new LinkedHashMap<>();
                node.path("headers").fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
                values.add(new ServerConfig(name, url, node.path("enabled").asBoolean(true), Map.copyOf(headers)));
                if (values.size() >= 20) break;
            }
            return values.stream().sorted(Comparator.comparing(ServerConfig::name)).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, String> resolvedHeaders(ServerConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        config.headers().forEach((name, configured) -> {
            String value = configured;
            if (configured.startsWith("env:")) value = System.getenv(configured.substring(4));
            if (value != null && !value.isBlank()) values.put(name, value);
        });
        return values;
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private record ServerConfig(String name, String url, boolean enabled, Map<String, String> headers) { }
    private record RemoteTool(String server, String remoteName, String namespacedName,
                              String description, Map<String, Object> inputSchema) { }
    private static final class ServerState {
        private volatile boolean initialized;
        private volatile boolean ready;
        private volatile String sessionId = "";
        private volatile String error = "";
        private volatile int consecutiveFailures;
        private volatile Instant openUntil = Instant.EPOCH;

        private boolean circuitOpen() { return Instant.now().isBefore(openUntil); }
        private void succeeded() {
            consecutiveFailures = 0;
            openUntil = Instant.EPOCH;
            error = "";
        }
        private void failed(String value) {
            error = value;
            ready = false;
            consecutiveFailures++;
            if (consecutiveFailures >= 3) {
                openUntil = Instant.now().plusSeconds(30);
                initialized = false;
                sessionId = "";
            }
        }
    }
    public record ServerStatus(String name, boolean enabled, boolean ready, String error) { }
}
