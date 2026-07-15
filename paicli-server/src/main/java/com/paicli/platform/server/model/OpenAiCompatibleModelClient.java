package com.paicli.platform.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.observability.RuntimeMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "paicli.model", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelClient implements ModelClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ModelProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final RuntimeMetrics metrics;
    private final Map<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestNanos = new AtomicLong();
    private URI endpoint;

    @Autowired
    public OpenAiCompatibleModelClient(ModelProperties properties, ObjectMapper mapper, RuntimeMetrics metrics) {
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = metrics;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
        if (properties.deepSeek()) builder.version(HttpClient.Version.HTTP_1_1);
        this.httpClient = builder.build();
    }

    public OpenAiCompatibleModelClient(ModelProperties properties, ObjectMapper mapper) {
        this(properties, mapper, null);
    }

    @PostConstruct
    void initialize() {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException("PAICLI_MODEL_API_KEY is required for openai-compatible provider");
        }
        String base = properties.baseUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
    }

    @Override
    public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
        ActiveRequest active = new ActiveRequest();
        if (runId != null && !runId.isBlank()) {
            ActiveRequest previous = activeRequests.putIfAbsent(runId, active);
            if (previous != null) throw new IllegalStateException("Model request is already active for run " + runId);
        }
        try {
            HttpResponse<InputStream> response = sendWithRetry(active, request);
            active.body = response.body();
            if (active.canceled) throw new CancellationException("Model request canceled");
            return readSse(response, listener == null ? ModelStreamListener.NO_OP : listener);
        } catch (CancellationException e) {
            throw new ModelRequestCanceledException("Model request canceled", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelRequestCanceledException("Model request interrupted", e);
        } catch (Exception e) {
            if (active.canceled) throw new ModelRequestCanceledException("Model request canceled", e);
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("Model call failed: " + e.getMessage(), e);
        } finally {
            if (runId != null && !runId.isBlank()) activeRequests.remove(runId, active);
            active.closeBody();
        }
    }

    private HttpResponse<InputStream> sendWithRetry(ActiveRequest active, ModelRequest request) throws Exception {
        List<String> models = properties.fallbackModel().isBlank()
                || properties.fallbackModel().equals(properties.model())
                ? List.of(properties.model()) : List.of(properties.model(), properties.fallbackModel());
        String lastError = "model request failed";
        for (String selectedModel : models) {
            for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
                if (active.canceled) throw new CancellationException("Model request canceled");
                acquireRatePermit();
                String body = mapper.writeValueAsString(requestBody(request, selectedModel));
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                        .header("Authorization", "Bearer " + properties.apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                        httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                active.future = future;
                if (active.canceled) future.cancel(true);
                try {
                    HttpResponse<InputStream> response = future.get();
                    if (response.statusCode() >= 200 && response.statusCode() < 300) return response;
                    String error;
                    try (InputStream input = response.body()) {
                        error = new String(input.readNBytes(16_001), StandardCharsets.UTF_8);
                    }
                    lastError = "Model HTTP " + response.statusCode() + ": " + error;
                    if (!retriable(response.statusCode())) break;
                } catch (CancellationException e) {
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                if (attempt < properties.maxAttempts()) {
                    if (metrics != null) metrics.modelRetry();
                    backoff(attempt, active);
                }
            }
        }
        throw new IllegalStateException(lastError);
    }

    private void acquireRatePermit() throws InterruptedException {
        long interval = 60_000_000_000L / properties.requestsPerMinute();
        while (true) {
            long now = System.nanoTime();
            long current = nextRequestNanos.get();
            long permit = Math.max(now, current);
            if (nextRequestNanos.compareAndSet(current, permit + interval)) {
                long wait = permit - now;
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                return;
            }
        }
    }

    private void backoff(int attempt, ActiveRequest active) throws InterruptedException {
        long wait = Math.min(30_000, properties.retryBaseMillis() * (1L << Math.min(attempt - 1, 6)));
        long deadline = System.currentTimeMillis() + wait;
        while (!active.canceled && System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.min(100, Math.max(1, deadline - System.currentTimeMillis())));
        }
        if (active.canceled) throw new CancellationException("Model request canceled");
    }

    private static boolean retriable(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    @Override
    public boolean cancel(String runId) {
        ActiveRequest active = activeRequests.get(runId);
        return active != null && active.cancel();
    }

    @Override
    public String name() {
        return "openai-compatible/" + properties.model();
    }

    private ObjectNode requestBody(ModelRequest request, String selectedModel) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", selectedModel);
        root.put("stream", true);
        root.put("max_tokens", request.maxOutputTokens());
        root.set("stream_options", mapper.createObjectNode().put("include_usage", true));
        String thinkingMode = "auto".equals(request.thinkingMode())
                ? properties.effectiveThinkingMode() : request.thinkingMode();
        if (!"auto".equals(thinkingMode)) {
            root.set("thinking", mapper.createObjectNode().put("type", thinkingMode));
        }
        String reasoningEffort = request.reasoningEffort().isBlank()
                ? ("disabled".equals(thinkingMode) ? "" : properties.effectiveReasoningEffort())
                : request.reasoningEffort();
        if (!reasoningEffort.isBlank()) root.put("reasoning_effort", reasoningEffort);
        ArrayNode messages = root.putArray("messages");
        for (ModelMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (message.images().isEmpty()) {
                node.put("content", message.content());
            } else {
                ArrayNode content = node.putArray("content");
                content.addObject().put("type", "text").put("text", message.content()
                        + "\n\n[Images are attached to this user message. Analyze the image content directly.]");
                for (ModelImage image : message.images()) {
                    ObjectNode imagePart = content.addObject();
                    imagePart.put("type", "image_url");
                    imagePart.putObject("image_url").put("url",
                            "data:" + image.mimeType() + ";base64," + image.base64());
                }
            }
            if (!message.reasoningContent().isBlank()) {
                node.put("reasoning_content", message.reasoningContent());
            }
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode toolCalls = node.putArray("tool_calls");
                for (ModelResponse.ToolPlan call : message.toolCalls()) {
                    ObjectNode callNode = toolCalls.addObject();
                    callNode.put("id", call.callId());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", mapper.writeValueAsString(call.arguments()));
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ModelToolDefinition definition : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.set("parameters", mapper.valueToTree(definition.parameters()));
            }
        }
        return root;
    }

    private ModelResponse readSse(HttpResponse<java.io.InputStream> response,
                                  ModelStreamListener listener) throws Exception {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedTokens = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JsonNode chunk = mapper.readTree(data);
                JsonNode usage = chunk.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(cachedTokens);
                    if (usage.has("prompt_cache_hit_tokens")) {
                        cachedTokens = usage.path("prompt_cache_hit_tokens").asInt(cachedTokens);
                    }
                }
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue;
                JsonNode delta = choices.get(0).path("delta");
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && contentNode.isTextual()) {
                    String text = contentNode.asText();
                    content.append(text);
                    listener.onContentDelta(text);
                }
                JsonNode reasoningNode = delta.get("reasoning_content");
                if (reasoningNode != null && reasoningNode.isTextual()) {
                    String text = reasoningNode.asText();
                    reasoningContent.append(text);
                    listener.onReasoningDelta(text);
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        int index = call.path("index").asInt(0);
                        ToolAccumulator accumulator = tools.computeIfAbsent(index, ignored -> new ToolAccumulator());
                        if (call.hasNonNull("id")) accumulator.id = call.path("id").asText();
                        JsonNode function = call.path("function");
                        if (function.hasNonNull("name")) accumulator.name.append(function.path("name").asText());
                        if (function.hasNonNull("arguments")) {
                            accumulator.arguments.append(function.path("arguments").asText());
                        }
                    }
                }
            }
        }
        List<ModelResponse.ToolPlan> plans = new ArrayList<>();
        for (ToolAccumulator tool : tools.values()) {
            if (tool.id == null || tool.id.isBlank() || tool.name.isEmpty()) {
                throw new IllegalStateException("Model returned an incomplete tool call");
            }
            JsonNode argumentsNode = tool.arguments.isEmpty()
                    ? mapper.createObjectNode() : mapper.readTree(tool.arguments.toString());
            if (!argumentsNode.isObject()) throw new IllegalStateException("Tool arguments must be a JSON object");
            Map<String, Object> arguments = mapper.convertValue(argumentsNode, MAP_TYPE);
            plans.add(new ModelResponse.ToolPlan(tool.id, tool.name.toString(), arguments));
        }
        return new ModelResponse(content.toString(), reasoningContent.toString(), plans,
                new ModelResponse.Usage(inputTokens, outputTokens, cachedTokens));
    }

    private static final class ActiveRequest {
        private volatile CompletableFuture<?> future;
        private volatile InputStream body;
        private volatile boolean canceled;

        private boolean cancel() {
            canceled = true;
            CompletableFuture<?> currentFuture = future;
            if (currentFuture != null) currentFuture.cancel(true);
            closeBody();
            return true;
        }

        private void closeBody() {
            InputStream currentBody = body;
            if (currentBody == null) return;
            try {
                currentBody.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ToolAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
