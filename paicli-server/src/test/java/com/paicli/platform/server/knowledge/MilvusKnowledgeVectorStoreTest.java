package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.MilvusProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusKnowledgeVectorStoreTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsDimensionCollectionAndUsesRestV2ForReplaceAndSearch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Request> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, mapper, requests));
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new MilvusProperties(true, endpoint, "root:Milvus", null,
                "paicli_knowledge", 3, 60);
        var store = new MilvusKnowledgeVectorStore(properties, mapper);

        store.replace("alpha", "architecture.md", "local:test", List.of(
                new KnowledgeVectorStore.VectorChunk(1, new float[]{1, 0}),
                new KnowledgeVectorStore.VectorChunk(2, new float[]{0, 1})));
        var result = store.search("alpha", "local:test", new float[]{1, 0}, 10);

        assertThat(requests).extracting(Request::path).containsExactly(
                "/v2/vectordb/collections/list",
                "/v2/vectordb/collections/create",
                "/v2/vectordb/entities/delete",
                "/v2/vectordb/entities/upsert",
                "/v2/vectordb/entities/search");
        assertThat(requests).allSatisfy(request -> assertThat(request.authorization())
                .isEqualTo("Bearer root:Milvus"));
        assertThat(requests).allSatisfy(request -> assertThat(request.body().path("dbName").asText())
                .isEqualTo("default"));
        JsonNode create = requests.get(1).body();
        assertThat(create.path("collectionName").asText()).isEqualTo("paicli_knowledge_d2");
        assertThat(create.path("dimension").asInt()).isEqualTo(2);
        assertThat(create.path("metricType").asText()).isEqualTo("COSINE");
        JsonNode upsert = requests.get(3).body();
        assertThat(upsert.path("data")).hasSize(2);
        assertThat(upsert.path("data").get(0).path("id").asText()).hasSize(64);
        assertThat(upsert.path("data").get(0).path("project_key").asText()).isEqualTo("alpha");
        assertThat(result.available()).isTrue();
        assertThat(result.scores()).containsEntry("vector-id", 0.875);
        assertThat(store.status()).satisfies(status -> {
            assertThat(status.configured()).isTrue();
            assertThat(status.reachable()).isTrue();
            assertThat(status.backend()).isEqualTo("milvus-rest");
        });
    }

    @Test
    void fallsBackWithoutThrowingWhenMilvusIsUnavailable() {
        var properties = new MilvusProperties(true, "http://127.0.0.1:1", "", "",
                "paicli_knowledge", 1, 60);
        var store = new MilvusKnowledgeVectorStore(properties, new ObjectMapper());

        var result = store.search("alpha", "local:test", new float[]{1, 0}, 10);

        assertThat(result.available()).isFalse();
        assertThat(store.status().reachable()).isFalse();
        assertThat(store.status().detail()).contains("search");
    }

    private static void handle(HttpExchange exchange, ObjectMapper mapper, List<Request> requests) throws IOException {
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        requests.add(new Request(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        String response = exchange.getRequestURI().getPath().endsWith("/search")
                ? "{\"code\":0,\"data\":[{\"id\":\"vector-id\",\"distance\":0.875}]}"
                : exchange.getRequestURI().getPath().endsWith("/list")
                ? "{\"code\":0,\"data\":[]}" : "{\"code\":0,\"data\":{}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Request(String path, String authorization, JsonNode body) { }
}
