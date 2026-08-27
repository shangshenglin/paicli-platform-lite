package com.paicli.platform.server.observability;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangfuseDeploymentFilesTest {
    @Test
    void composeContainsRequiredV4ServicesAndKeepsSecretsExternal() throws Exception {
        Path compose = Path.of("..", "deploy", "langfuse", "docker-compose.yml").normalize();
        Map<?, ?> document = new Yaml().load(Files.readString(compose));
        Map<?, ?> services = (Map<?, ?>) document.get("services");

        assertEquals(6, services.size());
        for (String required : new String[]{"langfuse-web", "langfuse-worker", "postgres",
                "clickhouse", "redis", "minio"}) {
            assertTrue(services.containsKey(required), required);
        }
        String source = Files.readString(compose);
        assertTrue(source.contains("LANGFUSE_WEB_IMAGE:-docker.langfuse.com/langfuse/langfuse:4"));
        assertTrue(source.contains("LANGFUSE_INIT_PROJECT_PUBLIC_KEY"));
        assertFalse(source.matches("(?s).*pk-lf-[a-f0-9]{16,}.*"));
        assertFalse(source.matches("(?s).*sk-lf-[a-f0-9]{16,}.*"));
    }

    @Test
    void managementScriptSupportsLifecycleAndCredentialInitialization() throws Exception {
        Path script = Path.of("..", "scripts", "langfuse.ps1").normalize();
        String source = Files.readString(script);

        assertTrue(source.contains("New-HexSecret"));
        assertTrue(source.contains("ValidateSet(\"init\", \"start\", \"pull\", \"stop\""));
        assertTrue(source.contains("PAICLI_LANGFUSE_CAPTURE_CONTENT"));
    }
}
