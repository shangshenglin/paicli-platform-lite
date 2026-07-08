package com.paicli.platform.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.SynchronousQueue;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorConfigurationTest {
    @Test
    void sseConnectionsScaleBeforeTheyQueue() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new ExecutorConfiguration().sseTaskExecutor();
        try {
            assertThat(executor.getMaxPoolSize()).isEqualTo(32);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isInstanceOf(SynchronousQueue.class);
        } finally {
            executor.shutdown();
        }
    }
}
