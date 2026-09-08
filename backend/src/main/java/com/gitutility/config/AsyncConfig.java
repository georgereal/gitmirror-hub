package com.gitutility.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "syncTaskExecutor")
    public Executor syncTaskExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("GitSync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "gitmirror.sync");
        return executor;
    }

    @Bean(name = "lfsDiscoveryExecutor", destroyMethod = "shutdown")
    public ExecutorService lfsDiscoveryExecutor(
            MeterRegistry meterRegistry,
            @Value("${git-utility.git.lfs-discovery-threads:4}") int threads) {
        return monitoredFixedPool(meterRegistry, Math.max(1, threads), 500, "LfsDiscover-", "gitmirror.lfs.discovery");
    }

    @Bean(name = "lfsTransferExecutor", destroyMethod = "shutdown")
    public ExecutorService lfsTransferExecutor(
            MeterRegistry meterRegistry,
            @Value("${git-utility.git.lfs-transfer-concurrency:4}") int threads) {
        return monitoredFixedPool(meterRegistry, Math.max(1, threads), 500, "LfsTransfer-", "gitmirror.lfs.transfer");
    }

    @Bean(name = "prCreateExecutor", destroyMethod = "shutdown")
    public ExecutorService prCreateExecutor(
            MeterRegistry meterRegistry,
            @Value("${git-utility.git.pr-create-concurrency:6}") int threads) {
        return monitoredFixedPool(meterRegistry, Math.max(1, threads), 1000, "PrCreate-", "gitmirror.pr.create");
    }

    private static ExecutorService monitoredFixedPool(MeterRegistry registry, int threads, int queueCapacity,
                                                      String threadPrefix, String metricName) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix(threadPrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(blockingQueueHandler());
        executor.initialize();
        return ExecutorServiceMetrics.monitor(registry, executor.getThreadPoolExecutor(), metricName);
    }

    /**
     * When the bounded work queue is full, wait for a slot instead of aborting
     * ({@link ThreadPoolExecutor.AbortPolicy}). In-pod LFS/PR fan-out then
     * throttles onto the configured thread count instead of throwing
     * {@link RejectedExecutionException} mid-job.
     */
    static RejectedExecutionHandler blockingQueueHandler() {
        return (task, executor) -> {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Task rejected — executor is shutdown");
            }
            try {
                executor.getQueue().put(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while waiting for a worker thread", e);
            }
        };
    }
}
