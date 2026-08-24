package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.MilvusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MilvusKnowledgeVectorStore implements KnowledgeVectorStore {
    private static final Logger log = LoggerFactory.getLogger(MilvusKnowledgeVectorStore.class);
    private static final int UPSERT_BATCH_SIZE = 100;
    private final MilvusProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();
    private volatile Instant lastSuccess;
    private volatile String lastError = "not contacted";

    @Autowired
    public MilvusKnowledgeVectorStore(MilvusProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds())).build());
    }

    MilvusKnowledgeVectorStore(MilvusProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
        if (properties.enabled()) URI.create(properties.endpoint());
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public void replace(String projectKey, String document, String provider, List<VectorChunk> chunks) {
        if (!enabled() || chunks == null || chunks.isEmpty()) return;
        int dimensions = dimensions(chunks);
        try {
            String collection = ensureCollection(dimensions);
            deleteByFilter(collection, documentFilter(projectKey, document, provider));
            for (int from = 0; from < chunks.size(); from += UPSERT_BATCH_SIZE) {
                int to = Math.min(chunks.size(), from + UPSERT_BATCH_SIZE);
                ObjectNode body = baseBody(collection);
                ArrayNode data = body.putArray("data");
                for (VectorChunk chunk : chunks.subList(from, to)) {
                    ObjectNode entity = data.addObject();
                    entity.put("id", KnowledgeVectorStore.entityId(projectKey, document, chunk.chunk()));
                    entity.set("vector", vector(chunk.vector()));
                    entity.put("project_key", projectKey);
                    entity.put("document", document);
                    entity.put("chunk", chunk.chunk());
                    entity.put("provider", provider);
                }
                post("/v2/vectordb/entities/upsert", body);
            }
            succeeded();
        } catch (Exception e) {
            failed("replace", e);
        }
    }

    @Override
    public void delete(String projectKey, String document, String provider, int dimensions) {
        if (!enabled() || dimensions <= 0) return;
        try {
            String collection = ensureCollection(dimensions);
            deleteByFilter(collection, documentFilter(projectKey, document, provider));
            succeeded();
        } catch (Exception e) {
            failed("delete", e);
        }
    }

    @Override
    public SearchResult search(String projectKey, String provider, float[] vector, int requestedLimit) {
        if (!enabled() || vector == null || vector.length == 0) return SearchResult.unavailable();
        try {
            String collection = ensureCollection(vector.length);
            ObjectNode body = baseBody(collection);
            body.putArray("data").add(vector(vector));
            body.put("annsField", "vector");
            body.put("filter", "project_key == " + literal(projectKey) + " and provider == " + literal(provider));
            body.put("limit", Math.max(1, Math.min(requestedLimit, properties.searchLimit())));
            body.putArray("outputFields").add("id");
            body.put("consistencyLevel", "Strong");
            body.putObject("searchParams").put("metricType", "COSINE");
            JsonNode response = post("/v2/vectordb/entities/search", body);
            Map<String, Double> scores = new LinkedHashMap<>();
            for (JsonNode hit : response.path("data")) {
                String id = hit.path("id").asText();
                if (!id.isBlank()) scores.put(id, hit.path("distance").asDouble());
            }
            succeeded();
            return new SearchResult(true, scores);
        } catch (Exception e) {
            failed("search", e);
            return SearchResult.unavailable();
        }
    }

    @Override
    public StoreStatus status() {
        if (!enabled()) return new StoreStatus(false, false, "local-json", "disabled");
        String detail = lastSuccess == null ? lastError
                : "last success " + lastSuccess + (lastError.isBlank() ? "" : "; last error " + lastError);
        return new StoreStatus(true, lastSuccess != null && lastError.isBlank(), "milvus-rest", detail);
    }

    private synchronized String ensureCollection(int dimensions) throws Exception {
        String collection = collection(dimensions);
        if (initializedCollections.contains(collection)) return collection;
        ObjectNode listBody = mapper.createObjectNode().put("dbName", properties.database());
        JsonNode listed = post("/v2/vectordb/collections/list", listBody);
        boolean exists = false;
        for (JsonNode name : listed.path("data")) {
            if (name.asText().equals(collection)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            ObjectNode create = baseBody(collection);
            create.put("dimension", dimensions);
            create.put("metricType", "COSINE");
            create.put("idType", "VarChar");
            create.put("primaryFieldName", "id");
            create.put("vectorFieldName", "vector");
            create.put("autoId", false);
            create.put("consistencyLevel", "Strong");
            create.putObject("params").put("max_length", 64).put("enableDynamicField", true);
            post("/v2/vectordb/collections/create", create);
        }
        initializedCollections.add(collection);
        return collection;
    }

    private void deleteByFilter(String collection, String filter) throws Exception {
        ObjectNode body = baseBody(collection);
        body.put("filter", filter);
        post("/v2/vectordb/entities/delete", body);
    }

    private ObjectNode baseBody(String collection) {
        return mapper.createObjectNode().put("dbName", properties.database()).put("collectionName", collection);
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.endpoint() + path))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Request-Timeout", Integer.toString(properties.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!properties.token().isBlank()) request.header("Authorization", "Bearer " + properties.token());
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Milvus HTTP " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        if (json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Milvus error " + json.path("code").asInt() + ": "
                    + json.path("message").asText("unknown error"));
        }
        return json;
    }

    private static int dimensions(List<VectorChunk> chunks) {
        int dimensions = chunks.get(0).vector() == null ? 0 : chunks.get(0).vector().length;
        if (dimensions <= 0) throw new IllegalArgumentException("Milvus vectors must not be empty");
        for (VectorChunk chunk : chunks) {
            if (chunk.vector() == null || chunk.vector().length != dimensions) {
                throw new IllegalArgumentException("Milvus vector dimensions must be consistent");
            }
        }
        return dimensions;
    }

    private ArrayNode vector(float[] values) {
        ArrayNode result = mapper.createArrayNode();
        for (float value : values) result.add(value);
        return result;
    }

    private String collection(int dimensions) {
        return properties.collectionPrefix() + "_d" + dimensions;
    }

    private static String documentFilter(String projectKey, String document, String provider) {
        return "project_key == " + literal(projectKey) + " and document == " + literal(document)
                + " and provider == " + literal(provider);
    }

    private static String literal(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    private void succeeded() {
        lastSuccess = Instant.now();
        lastError = "";
    }

    private void failed(String operation, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        lastError = operation + ": " + message;
        log.warn("Milvus {} failed; local knowledge index remains available: {}", operation, message);
    }
}
