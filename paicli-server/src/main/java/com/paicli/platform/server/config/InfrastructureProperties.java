package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

@ConfigurationProperties(prefix = "paicli.infrastructure")
public record InfrastructureProperties(
        String runQueue,
        String coordination,
        String artifactStorage
) {
    public InfrastructureProperties {
        runQueue = backend(runQueue);
        coordination = backend(coordination);
        artifactStorage = backend(artifactStorage);
    }

    public static InfrastructureProperties local() {
        return new InfrastructureProperties("local", "local", "local");
    }

    public void requireLocalRunQueue() {
        requireLocal("paicli.infrastructure.run-queue", runQueue, "Kafka");
    }

    public void requireLocalCoordination() {
        requireLocal("paicli.infrastructure.coordination", coordination, "Redis");
    }

    public void requireLocalArtifactStorage() {
        requireLocal("paicli.infrastructure.artifact-storage", artifactStorage, "MinIO");
    }

    private static String backend(String value) {
        return value == null || value.isBlank() ? "local" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireLocal(String property, String value, String reservedAdapter) {
        if (!"local".equals(value)) {
            throw new IllegalArgumentException(property + " currently supports only local; "
                    + reservedAdapter + " adapter interfaces are reserved but not implemented");
        }
    }
}
