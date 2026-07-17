package com.paicli.platform.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.observability.RuntimeMetrics;
import com.paicli.platform.server.store.SqliteRuntimeStore;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;

@Component
@ConditionalOnProperty(prefix = "paicli.model", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelClient implements ModelClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ModelProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final RuntimeMetrics metrics;
    private final SqliteRuntimeStore store;
    private final Map<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestNanos = new AtomicLong();
    private final CircuitState circuit = new CircuitState();
    private URI endpoint;

    @Autowired
    public OpenAiCompatibleModelClient(ModelProperties properties, ObjectMapper mapper, RuntimeMetrics metrics,
                                       SqliteRuntimeStore store) {
        this.properties = properties;
        this.mapper = mapper;
        this.metrics = metrics;
        this.store = store;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
        if (properties.deepSeek()) builder.version(HttpClient.Version.HTTP_1_1);
        this.httpClient = builder.build();
    }

    public OpenAiCompatibleModelClient(ModelProperties properties, ObjectMapper mapper, RuntimeMetrics metrics) {
        this(properties, mapper, metrics, null);
    }

    public OpenAiCompatibleModelClient(ModelProperties properties, ObjectMapper mapper) {
        this(properties, mapper, null);
    }

    @PostConstruct
    void initialize() {
        String base = properties.baseUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
    }

    @Override
    public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
        if (!circuit.tryAcquire(properties.circuitFailureThreshold(), properties.circuitOpenSeconds())) {
            throw new IllegalStateException("model circuit is open");
        }
        ActiveRequest active = new ActiveRequest(runId);
        if (runId != null && !runId.isBlank()) {
            ActiveRequest previous = activeRequests.putIfAbsent(runId, active);
            if (previous != null) throw new IllegalStateException("Model request is already active for run " + runId);
        }
        try {
            HttpResponse<InputStream> response = sendWithRetry(active, request);
            active.body = response.body();
            if (active.canceled) throw new CancellationException("Model request canceled");
            ModelResponse result = readSse(response, listener == null ? ModelStreamListener.NO_OP : listener);
            finishAttempt(active, "SUCCESS", active.attemptHttpStatus, null);
            circuit.succeeded();
            return result;
        } catch (CancellationException e) {
            finishAttempt(active, "CANCELED", active.attemptHttpStatus, e.getMessage());
            throw new ModelRequestCanceledException("Model request canceled", e);
        } catch (InterruptedException e) {
            finishAttempt(active, "CANCELED", active.attemptHttpStatus, e.getMessage());
            Thread.currentThread().interrupt();
            throw new ModelRequestCanceledException("Model request interrupted", e);
        } catch (Exception e) {
            finishAttempt(active, "FAILED", active.attemptHttpStatus, e.getMessage());
            if (active.canceled) throw new ModelRequestCanceledException("Model request canceled", e);
            circuit.failed(properties.circuitFailureThreshold(), properties.circuitOpenSeconds());
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("Model call failed: " + e.getMessage(), e);
        } finally {
            if (runId != null && !runId.isBlank()) activeRequests.remove(runId, active);
            active.closeBody();
        }
    }

    private HttpResponse<InputStream> sendWithRetry(ActiveRequest active, ModelRequest request) throws Exception {
        ModelRoute route = request.route();
        String primaryModel = route == null ? properties.model() : route.model();
        String fallbackModel = route == null ? properties.fallbackModel() : route.fallbackModel();
        String apiKey = route == null ? properties.apiKey() : route.apiKey();
        URI selectedEndpoint = route == null ? endpoint : endpoint(route.baseUrl());
        List<String> models = fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(primaryModel)
                ? List.of(primaryModel) : List.of(primaryModel, fallbackModel);
        String lastError = "model request failed";
        int attemptOrdinal = 0;
        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            String selectedModel = models.get(modelIndex);
            for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
                attemptOrdinal++;
                if (active.canceled) throw new CancellationException("Model request canceled");
                acquireRatePermit(active);
                String body = mapper.writeValueAsString(requestBody(request, selectedModel));
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(selectedEndpoint)
                        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (apiKey != null && !apiKey.isBlank()) requestBuilder.header("Authorization", "Bearer " + apiKey);
                HttpRequest httpRequest = requestBuilder.build();
                active.attemptId = startAttempt(active.runId, selectedModel, attemptOrdinal);
                active.attemptHttpStatus = null;
                CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                        httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                active.future = future;
                if (active.canceled) future.cancel(true);
                long retryAfterMillis = 0;
                try {
                    HttpResponse<InputStream> response = future.get();
                    active.attemptHttpStatus = response.statusCode();
                    if (response.statusCode() >= 200 && response.statusCode() < 300) return response;
                    String error;
                    try (InputStream input = response.body()) {
                        error = new String(input.readNBytes(16_001), StandardCharsets.UTF_8);
                    }
                    lastError = "Model HTTP " + response.statusCode() + ": " + error;
                    retryAfterMillis = retryAfterMillis(response);
                    boolean willRetry = retriable(response.statusCode())
                            && (attempt < properties.maxAttempts() || modelIndex + 1 < models.size());
                    finishAttempt(active, willRetry ? "RETRY" : "FAILED", response.statusCode(), lastError);
                    if (!retriable(response.statusCode())) break;
                } catch (CancellationException e) {
                    finishAttempt(active, "CANCELED", active.attemptHttpStatus, e.getMessage());
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    boolean willRetry = attempt < properties.maxAttempts() || modelIndex + 1 < models.size();
                    finishAttempt(active, willRetry ? "RETRY" : "FAILED", active.attemptHttpStatus, lastError);
                }
                if (attempt < properties.maxAttempts()) {
                    if (metrics != null) metrics.modelRetry();
                    backoff(attempt, retryAfterMillis, active);
                }
            }
        }
        throw new IllegalStateException(lastError);
    }

    private void acquireRatePermit(ActiveRequest active) throws InterruptedException {
        long interval = 60_000_000_000L / properties.requestsPerMinute();
        while (true) {
            long now = System.nanoTime();
            long current = nextRequestNanos.get();
            long permit = Math.max(now, current);
            if (nextRequestNanos.compareAndSet(current, permit + interval)) {
                long wait = permit - now;
                while (wait > 0) {
                    if (active.canceled) throw new CancellationException("Model request canceled");
                    long slice = Math.min(wait, 100_000_000L);
                    Thread.sleep(slice / 1_000_000L, (int) (slice % 1_000_000L));
                    wait -= slice;
                }
                return;
            }
        }
    }

    private void backoff(int attempt, long retryAfterMillis, ActiveRequest active) throws InterruptedException {
        long exponential = Math.min(30_000, properties.retryBaseMillis() * (1L << Math.min(attempt - 1, 6)));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, exponential / 2 + 1));
        long wait = Math.min(60_000, Math.max(retryAfterMillis, exponential / 2 + jitter));
        long deadline = System.currentTimeMillis() + wait;
        while (!active.canceled && System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.min(100, Math.max(1, deadline - System.currentTimeMillis())));
        }
        if (active.canceled) throw new CancellationException("Model request canceled");
    }

    private static boolean retriable(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isBlank()) return 0;
        try { return Math.min(60_000, Math.max(0, Long.parseLong(value) * 1_000)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String startAttempt(String runId, String modelName, int ordinal) {
        if (store == null) return null;
        try { return store.startModelAttempt(runId, "openai-compatible", modelName, ordinal); }
        catch (Exception ignored) { return null; }
    }

    private void finishAttempt(ActiveRequest active, String status, Integer httpStatus, String error) {
        String attemptId = active.attemptId;
        if (store == null || attemptId == null) return;
        active.attemptId = null;
        try { store.finishModelAttempt(attemptId, status, httpStatus, error); }
        catch (Exception ignored) { }
    }

    private static URI endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (base.isBlank()) throw new IllegalArgumentException("model profile baseUrl is required");
        URI value = URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
        if (!"http".equalsIgnoreCase(value.getScheme()) && !"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("model profile baseUrl must use http or https");
        }
        return value;
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
            ExecutorService lineReader = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "paicli-model-stream-reader");
                thread.setDaemon(true);
                return thread;
            });
            try {
                String line;
                while ((line = readLine(reader, lineReader)) != null) {
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
            } finally {
                lineReader.shutdownNow();
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

    private String readLine(BufferedReader reader, ExecutorService executor) throws Exception {
        Future<String> future = executor.submit(reader::readLine);
        try {
            return future.get(properties.streamIdleTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Model stream was idle for more than "
                    + properties.streamIdleTimeoutSeconds() + " seconds", e);
        }
    }

    private static final class ActiveRequest {
        private final String runId;
        private volatile CompletableFuture<?> future;
        private volatile InputStream body;
        private volatile boolean canceled;
        private volatile String attemptId;
        private volatile Integer attemptHttpStatus;

        private ActiveRequest(String runId) {
            this.runId = runId == null ? "" : runId;
        }

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

    private static final class CircuitState {
        private int failures;
        private long openUntilMillis;
        private boolean halfOpenProbe;

        private synchronized boolean tryAcquire(int threshold, long openSeconds) {
            long now = System.currentTimeMillis();
            if (openUntilMillis > now) return false;
            if (failures < threshold) return true;
            if (halfOpenProbe) return false;
            halfOpenProbe = true;
            return true;
        }

        private synchronized void succeeded() {
            failures = 0;
            openUntilMillis = 0;
            halfOpenProbe = false;
        }

        private synchronized void failed(int threshold, long openSeconds) {
            failures++;
            halfOpenProbe = false;
            if (failures >= threshold) openUntilMillis = System.currentTimeMillis() + openSeconds * 1_000;
        }
    }
}
