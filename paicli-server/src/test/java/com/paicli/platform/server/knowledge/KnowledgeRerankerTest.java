package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.RerankerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRerankerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsTeiResponseIndexesBackToCandidateIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            assertThat(request.path("query").asText()).isEqualTo("Milvus 向量检索");
            assertThat(request.path("texts")).hasSize(2);
            byte[] response = "[{\"index\":1,\"score\":0.93},{\"index\":0,\"score\":0.17}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        RerankerProperties properties = new RerankerProperties(true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "test-reranker", 30, 5, 4_000);
        KnowledgeReranker reranker = new KnowledgeReranker(properties, mapper, HttpClient.newHttpClient());

        var result = reranker.rerank("Milvus 向量检索", List.of(
                candidate(41, "Milvus"), candidate(73, "向量数据库")));

        assertThat(result.crossEncoder()).isTrue();
        assertThat(result.provider()).isEqualTo("tei-cross-encoder");
        assertThat(result.scores()).containsEntry(41, 0.17).containsEntry(73, 0.93);
        assertThat(reranker.status().reachable()).isTrue();
    }

    @Test
    void fallsBackForTheWholeCandidatePoolWhenTeiFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            byte[] response = "{\"error\":\"model unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        RerankerProperties properties = new RerankerProperties(true,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "test-reranker", 30, 5, 4_000);
        KnowledgeReranker reranker = new KnowledgeReranker(properties,
                new ObjectMapper(), HttpClient.newHttpClient());

        var result = reranker.rerank("Milvus", List.of(candidate(1, "Milvus"), candidate(2, "Docker")));

        assertThat(result.crossEncoder()).isFalse();
        assertThat(result.provider()).isEqualTo("local-deterministic");
        assertThat(result.scores()).containsOnlyKeys(1, 2);
        assertThat(result.scores().get(1)).isGreaterThan(result.scores().get(2));
        assertThat(reranker.status().reachable()).isFalse();
        assertThat(reranker.status().detail()).contains("TEI HTTP 503");
    }

    private static KnowledgeReranker.RerankCandidate candidate(int id, String text) {
        return new KnowledgeReranker.RerankCandidate(id, "", text, 1.0, 0.5, 0.02);
    }
}
