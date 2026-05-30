package com.pearl.astrology.match.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures a dedicated thread pool for real-time (event-driven) match generation.
 * Separate from the Spring Batch thread pool to avoid resource contention.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor used by {@link com.pearl.astrology.match.service.MatchGenerationService}.
     * Sized to handle bursts of new-user registrations without overwhelming MongoDB.
     */
    @Bean(name = "matchGenerationExecutor")
    public Executor matchGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("match-gen-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
