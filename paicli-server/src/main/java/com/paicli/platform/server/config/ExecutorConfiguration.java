package com.paicli.platform.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ExecutorConfiguration {
    @Bean("runTaskExecutor")
    TaskExecutor runTaskExecutor(PlatformProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerCount());
        executor.setMaxPoolSize(properties.workerCount());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("paicli-run-");
        executor.initialize();
        return executor;
    }

    @Bean("sseTaskExecutor")
    TaskExecutor sseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(32);
        // SSE tasks are long lived. A normal queue would keep the pool at two threads and
        // starve later browser tabs instead of allowing maxPoolSize to take effect.
        executor.setQueueCapacity(0);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("paicli-sse-");
        executor.initialize();
        return executor;
    }

    @Bean("notificationTaskExecutor")
    TaskExecutor notificationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("paicli-notify-");
        executor.initialize();
        return executor;
    }
}
