package com.paicli.platform.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfrastructurePropertiesTest {
    @Test
    void defaultsToLocalBackends() {
        InfrastructureProperties properties = new InfrastructureProperties(null, "", "local");

        assertThat(properties.runQueue()).isEqualTo("local");
        assertThat(properties.coordination()).isEqualTo("local");
        assertThat(properties.artifactStorage()).isEqualTo("local");
    }

    @Test
    void rejectsReservedExternalBackendsUntilAdaptersExist() {
        assertThatThrownBy(() -> new InfrastructureProperties("kafka", "local", "local").requireLocalRunQueue())
                .hasMessageContaining("Kafka adapter interfaces are reserved but not implemented");
        assertThatThrownBy(() -> new InfrastructureProperties("local", "redis", "local").requireLocalCoordination())
                .hasMessageContaining("Redis adapter interfaces are reserved but not implemented");
        assertThatThrownBy(() -> new InfrastructureProperties("local", "local", "minio").requireLocalArtifactStorage())
                .hasMessageContaining("MinIO adapter interfaces are reserved but not implemented");
    }
}
