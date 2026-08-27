package com.paicli.platform.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LangfusePropertiesTest {
    @Test
    void normalizesEndpointAndDefaults() {
        LangfuseProperties properties = new LangfuseProperties(
                true, "https://langfuse.example/", "public", "secret", "", false, 0, 0);

        assertEquals("https://langfuse.example/api/public/otel/v1/traces", properties.tracesEndpoint());
        assertEquals("local", properties.environment());
        assertEquals(20_000, properties.maxContentChars());
        assertEquals(3_000, properties.exportTimeoutMillis());
    }

    @Test
    void acceptsOtelBaseEndpoint() {
        LangfuseProperties properties = new LangfuseProperties(
                true, "https://langfuse.example/api/public/otel", "public", "secret",
                "test", true, 10_000, 5_000);

        assertEquals("https://langfuse.example/api/public/otel/v1/traces", properties.tracesEndpoint());
    }

    @Test
    void rejectsIncompleteEnabledConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new LangfuseProperties(
                true, "", "public", "", "local", false, 20_000, 3_000));
    }

    @Test
    void disabledConfigurationNeedsNoCredentials() {
        LangfuseProperties properties = new LangfuseProperties(
                false, "", "", "", "", false, 0, 0);

        assertEquals("local", properties.environment());
    }
}
