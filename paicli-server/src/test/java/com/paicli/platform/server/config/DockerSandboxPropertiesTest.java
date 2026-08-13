package com.paicli.platform.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerSandboxPropertiesTest {
    @Test
    void appliesSafeResourceAndNetworkDefaults() {
        DockerSandboxProperties properties = new DockerSandboxProperties(
                null, null, null, null, 0, 0, null, null, null, 0, 0);

        assertThat(properties.executable()).isEqualTo("docker");
        assertThat(properties.image()).isEqualTo("paicli-sandbox-agent:0.6.0");
        assertThat(properties.network()).isEqualTo("none");
        assertThat(properties.memory()).isEqualTo("1g");
        assertThat(properties.cpus()).isEqualTo(1.0);
        assertThat(properties.pidsLimit()).isEqualTo(128);
        assertThat(properties.tmpfsSize()).isEqualTo("256m");
        assertThat(properties.homeTmpfsSize()).isEqualTo("512m");
        assertThat(properties.shmSize()).isEqualTo("64m");
        assertThat(properties.startupTimeoutSeconds()).isEqualTo(30);
        assertThat(properties.commandTimeoutSeconds()).isEqualTo(90);
    }

    @Test
    void rejectsUnsafeNamesAndInvalidResourceSizes() {
        assertThatThrownBy(() -> new DockerSandboxProperties(
                "docker", "sandbox:test", "container:other", "1g", 1, 128,
                "256m", "512m", "64m", 30, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("network");
        assertThatThrownBy(() -> new DockerSandboxProperties(
                "docker", "sandbox:test", "none", "unlimited", 1, 128,
                "256m", "512m", "64m", 30, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory or tmpfs");
        assertThatThrownBy(() -> new DockerSandboxProperties(
                "docker", "sandbox:test", "none", "1g", Double.NaN, 128,
                "256m", "512m", "64m", 30, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
