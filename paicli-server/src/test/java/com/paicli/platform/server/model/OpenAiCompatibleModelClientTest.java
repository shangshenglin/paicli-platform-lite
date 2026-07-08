package com.paicli.platform.server.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.ModelProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleModelClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesStreamingContentAndUsage() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":2,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":3}}}\n\n"
                + "data: [DONE]\n\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ModelProperties properties = new ModelProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test-model",
                8_000, 1_000, 0.75, 6, 1_000, 30, "auto", "");
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, new ObjectMapper());
        client.initialize();
        StringBuilder deltas = new StringBuilder();

        ModelResponse response = client.complete(new ModelRequest(
                List.of(ModelMessage.user("hello")), List.of(), 1_000), deltas::append);

        assertThat(response.content()).isEqualTo("你好");
        assertThat(deltas).hasToString("你好");
        assertThat(response.usage()).isEqualTo(new ModelResponse.Usage(12, 2, 3));
    }

    @Test
    void preservesDeepSeekV4ReasoningAcrossToolCalls() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先检查目录\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"我先检查文件。\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_ds\","
                + "\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}}]}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":9,"
                + "\"prompt_cache_hit_tokens\":8}}\n\n"
                + "data: [DONE]\n\n";
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ModelProperties properties = new ModelProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "deepseek-v4-flash",
                1_000_000, 16_384, 0.75, 6, 16_000, 30, "enabled", "max");
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, mapper);
        client.initialize();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ModelStreamListener listener = new ModelStreamListener() {
            @Override public void onContentDelta(String delta) { content.append(delta); }
            @Override public void onReasoningDelta(String delta) { reasoning.append(delta); }
        };

        ModelResponse first = client.complete(new ModelRequest(
                List.of(ModelMessage.user("查看目录")), List.of(), 16_384), listener);

        assertThat(first.content()).isEqualTo("我先检查文件。");
        assertThat(first.reasoningContent()).isEqualTo("先检查目录");
        assertThat(content).hasToString("我先检查文件。");
        assertThat(reasoning).hasToString("先检查目录");
        assertThat(first.toolCall()).isEqualTo(new ModelResponse.ToolPlan(
                "call_ds", "list_dir", Map.of("path", ".")));
        assertThat(first.usage()).isEqualTo(new ModelResponse.Usage(21, 9, 8));
        var firstBody = mapper.readTree(requestBody.get());
        assertThat(firstBody.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(firstBody.path("reasoning_effort").asText()).isEqualTo("max");

        ModelMessage assistant = new ModelMessage("assistant", first.content(), null,
                List.of(first.toolCall()), first.reasoningContent());
        client.complete(new ModelRequest(List.of(ModelMessage.user("查看目录"), assistant,
                ModelMessage.tool("call_ds", "README.md")), List.of(), 16_384));
        var secondBody = mapper.readTree(requestBody.get());
        assertThat(secondBody.path("messages").get(1).path("reasoning_content").asText())
                .isEqualTo("先检查目录");

        client.complete(new ModelRequest(List.of(ModelMessage.user("fast")), List.of(),
                1_024, "disabled", ""));
        var fastBody = mapper.readTree(requestBody.get());
        assertThat(fastBody.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(fastBody.has("reasoning_effort")).isFalse();
    }

    @Test
    void preservesEveryParallelToolCallInProviderOrder() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}"
                + "]}}]}\n\ndata: [DONE]\n\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ModelProperties properties = new ModelProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test-model",
                8_000, 1_000, 0.75, 6, 1_000, 30, "auto", "");
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, new ObjectMapper());
        client.initialize();

        ModelResponse response = client.complete(new ModelRequest(
                List.of(ModelMessage.user("inspect")), List.of(), 1_000));

        assertThat(response.toolCalls()).containsExactly(
                new ModelResponse.ToolPlan("call_a", "list_dir", Map.of("path", ".")),
                new ModelResponse.ToolPlan("call_b", "read_file", Map.of("path", "README.md")));
    }

    @Test
    void serializesUserImagesAsOpenAiContentParts() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ModelProperties properties = new ModelProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "vision-model",
                8_000, 1_000, 0.75, 6, 1_000, 30, "auto", "");
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, mapper);
        client.initialize();
        ModelMessage message = new ModelMessage("user", "分析截图", null, List.of(), "",
                List.of(new ModelImage("image/png", "aGVsbG8=", "screen.png")));

        client.complete(new ModelRequest(List.of(message), List.of(), 1_000));

        var content = mapper.readTree(requestBody.get()).path("messages").get(0).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,aGVsbG8=");
    }

    @Test
    void cancelClosesAnActiveStreamingRequest() throws Exception {
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch finishHandler = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"started\"}}]}\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                streaming.countDown();
                finishHandler.await(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        ModelProperties properties = new ModelProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test-model",
                8_000, 1_000, 0.75, 6, 1_000, 30, "auto", "");
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, new ObjectMapper());
        client.initialize();
        CompletableFuture<ModelResponse> result = CompletableFuture.supplyAsync(() -> client.complete(
                "run_cancel", new ModelRequest(List.of(ModelMessage.user("wait")), List.of(), 1_000),
                ModelStreamListener.NO_OP));
        assertThat(streaming.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(client.cancel("run_cancel")).isTrue();
        finishHandler.countDown();
        assertThatThrownBy(result::join).hasCauseInstanceOf(ModelRequestCanceledException.class);
    }

    @Test
    void retriesBeforeStreamingThenUsesConfiguredFallbackModel() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> successfulBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int request = requests.incrementAndGet();
            if (request <= 2) {
                byte[] error = "temporary".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, error.length);
                exchange.getResponseBody().write(error);
            } else {
                successfulBody.set(body);
                byte[] stream = "data: {\"choices\":[{\"delta\":{\"content\":\"fallback-ok\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, stream.length);
                exchange.getResponseBody().write(stream);
            }
            exchange.close();
        });
        server.start();
        ModelProperties properties = new ModelProperties("openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "primary-model",
                8_000, 1_000, 0.80, 6, 1_000, 30, "auto", "",
                2, 1, 10_000, "fallback-model", 30, 200_000);
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(properties, mapper);
        client.initialize();

        ModelResponse response = client.complete(new ModelRequest(
                List.of(ModelMessage.user("retry")), List.of(), 1_000));

        assertThat(requests).hasValue(3);
        assertThat(response.content()).isEqualTo("fallback-ok");
        assertThat(mapper.readTree(successfulBody.get()).path("model").asText())
                .isEqualTo("fallback-model");
    }
}
