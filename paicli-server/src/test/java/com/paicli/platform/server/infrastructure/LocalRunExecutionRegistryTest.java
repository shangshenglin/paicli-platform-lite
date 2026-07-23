package com.paicli.platform.server.infrastructure;

import com.paicli.platform.server.config.InfrastructureProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRunExecutionRegistryTest {
    @Test
    void preventsDuplicateRunExecutionInCurrentProcess() {
        LocalRunExecutionRegistry registry = new LocalRunExecutionRegistry(InfrastructureProperties.local());

        assertThat(registry.tryEnter("run-1")).isTrue();
        assertThat(registry.tryEnter("run-1")).isFalse();

        registry.leave("run-1");

        assertThat(registry.tryEnter("run-1")).isTrue();
    }
}
