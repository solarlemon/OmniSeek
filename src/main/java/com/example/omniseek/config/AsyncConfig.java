package com.example.omniseek.config;

import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {
    @Bean(name = "compactionExecutor")
    public Executor compactionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("compaction-");
        executor.initialize();
        return executor;
    }
}
