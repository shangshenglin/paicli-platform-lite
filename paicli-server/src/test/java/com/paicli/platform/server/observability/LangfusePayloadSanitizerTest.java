package com.paicli.platform.server.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.LangfuseProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangfusePayloadSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recursivelyRedactsSensitiveKeys() throws Exception {
        LangfusePayloadSanitizer sanitizer = sanitizer(true, 20_000);

        JsonNode value = mapper.readTree(sanitizer.sanitize(Map.of(
                "authorization", "Bearer secret",
                "nested", Map.of("api_key", "key", "password", "pass", "safe", "ok"))));

        assertEquals("[REDACTED]", value.path("authorization").asText());
        assertEquals("[REDACTED]", value.path("nested").path("api_key").asText());
        assertEquals("[REDACTED]", value.path("nested").path("password").asText());
        assertEquals("ok", value.path("nested").path("safe").asText());
    }

    @Test
    void returnsMetadataOnlyWhenCaptureIsDisabled() throws Exception {
        JsonNode value = mapper.readTree(sanitizer(false, 20_000).sanitize(Map.of("prompt", "private")));

        assertFalse(value.path("captured").asBoolean());
        assertTrue(value.path("characters").asInt() > 0);
        assertFalse(value.toString().contains("private"));
    }

    @Test
    void wrapsOversizedContentAsTruncatedPreview() throws Exception {
        JsonNode value = mapper.readTree(sanitizer(true, 32).sanitize(Map.of("content", "x".repeat(200))));

        assertTrue(value.path("truncated").asBoolean());
        assertTrue(value.path("originalCharacters").asInt() > 32);
        assertEquals(32, value.path("preview").asText().length());
    }

    private LangfusePayloadSanitizer sanitizer(boolean capture, int maxChars) {
        return new LangfusePayloadSanitizer(mapper, new LangfuseProperties(
                false, "", "", "", "test", capture, maxChars, 3_000));
    }
}
