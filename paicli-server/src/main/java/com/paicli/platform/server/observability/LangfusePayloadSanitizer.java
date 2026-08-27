package com.paicli.platform.server.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.LangfuseProperties;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LangfusePayloadSanitizer {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "apikey", "token", "accesstoken", "refreshtoken",
            "secret", "password", "credential", "cookie", "setcookie");
    private final ObjectMapper mapper;
    private final boolean captureContent;
    private final int maxContentChars;

    public LangfusePayloadSanitizer(ObjectMapper mapper, LangfuseProperties properties) {
        this.mapper = mapper;
        this.captureContent = properties.captureContent();
        this.maxContentChars = properties.maxContentChars();
    }

    public String sanitize(Object value) {
        return serialize(value, captureContent);
    }

    public String sanitizeMetadata(Object value) {
        return serialize(value, true);
    }

    private String serialize(Object value, boolean includeContent) {
        try {
            JsonNode redacted = redact(mapper.valueToTree(value));
            String json = mapper.writeValueAsString(redacted);
            if (!includeContent) {
                return mapper.writeValueAsString(Map.of(
                        "captured", false,
                        "characters", json.length()));
            }
            if (json.length() <= maxContentChars) return json;
            return mapper.writeValueAsString(Map.of(
                    "truncated", true,
                    "originalCharacters", json.length(),
                    "preview", json.substring(0, maxContentChars)));
        } catch (Exception error) {
            return "{\"serializationError\":true}";
        }
    }

    public boolean capturesContent() {
        return captureContent;
    }

    private JsonNode redact(JsonNode node) {
        if (node == null || node.isNull()) return mapper.nullNode();
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_KEYS.contains(normalize(field.getKey()))) {
                    object.put(field.getKey(), "[REDACTED]");
                } else {
                    object.set(field.getKey(), redact(field.getValue()));
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            node.forEach(value -> array.add(redact(value)));
            return array;
        }
        return node;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
