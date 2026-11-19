package com.example.reviewbot.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AsyncConfig {

    /**
     * Runs one agent per file on a virtual thread. Concurrency per pull request is bounded by
     * {@code reviewbot.review.max-concurrent-files} inside the pipeline, not by the pool.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService reviewExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("file-review-", 0).factory());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
