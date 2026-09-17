package com.gitutility.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pod-wide push-batch concurrency governance. Each sync job gets a dynamic "wave size" —
 * how many of its own destination push batches may run in parallel — computed from a fair
 * share of the shared push-batch pool across concurrently-running jobs, live CPU/heap/disk
 * headroom ({@link SystemResourceMonitor}), and a pod-wide cooldown that collapses fan-out
 * after a detected Git throttle/429. A semaphore-based per-destination-host cap bounds
 * simultaneous push connections to a single host across all jobs, independent of the
 * whole-job {@code pushConcurrencyLimiter} in {@code QueueConsumerService}.
 */
@Service
@Slf4j
public class PushBatchConcurrencyService {

    @Value("${git-utility.git.push-batch-concurrency:2}")
    private int maxPerJobConcurrency;

    @Value("${git-utility.git.push-batch-pool-max-threads:8}")
    private int poolMaxThreads;

    @Value("${git-utility.git.push-batch-per-host-max:4}")
    private int perHostMax;

    @Value("${git-utility.git.push-batch-throttle-cooldown-seconds:60}")
    private long throttleCooldownSeconds;

    @Value("${git-utility.git.push-batch-cpu-high-watermark:0.90}")
    private double cpuHighWatermark;

    @Value("${git-utility.git.push-batch-heap-high-watermark-percent:85}")
    private double heapHighWatermarkPercent;

    @Value("${git-utility.git.push-batch-disk-low-watermark-percent:10}")
    private double diskLowWatermarkPercent;

    private final Set<Long> activeJobs = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Semaphore> hostPermits = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightBatches = new AtomicInteger();
    private volatile long throttleCooldownUntilMs = 0L;

    private final SystemResourceMonitor resourceMonitor;

    public PushBatchConcurrencyService(
            @Value("${git-utility.workspace-dir:/tmp/git-utility-mirrors}") String workspaceDir) {
        this.resourceMonitor = new SystemResourceMonitor(workspaceDir);
    }

    /** Marks a sync job as being in its push phase (fair-share wave sizing). */
    public void jobBegin(Long jobId) {
        if (jobId != null) {
            activeJobs.add(jobId);
        }
    }

    /** Marks a sync job as done with its push phase. */
    public void jobEnd(Long jobId) {
        if (jobId != null) {
            activeJobs.remove(jobId);
        }
    }

    /** In-flight batch tracking for the Internals snapshot. */
    public void beginBatch() {
        inFlightBatches.incrementAndGet();
    }

    public void endBatch() {
        inFlightBatches.decrementAndGet();
    }

    public int activeJobCount() {
        return activeJobs.size();
    }

    public int inFlightBatchCount() {
        return inFlightBatches.get();
    }

    /**
     * Wave size for a job: {@code min(configured per-job max, fair share of the pool across
     * active jobs)}, collapsing to {@code 1} during a throttle cooldown or when live resource
     * headroom is tight.
     */
    public int waveSize(Long jobId) {
        int configured = Math.max(1, maxPerJobConcurrency);
        if (isThrottleCooldownActive()) {
            return 1;
        }
        int active = Math.max(1, activeJobs.size());
        int fairShare = Math.max(1, (int) Math.ceil((double) Math.max(1, poolMaxThreads) / active));
        int wave = Math.min(configured, fairShare);
        if (wave > 1 && !hasResourceHeadroom()) {
            return 1;
        }
        return Math.max(1, wave);
    }

    public boolean isThrottleCooldownActive() {
        return System.currentTimeMillis() < throttleCooldownUntilMs;
    }

    public long throttleCooldownRemainingMs() {
        return Math.max(0L, throttleCooldownUntilMs - System.currentTimeMillis());
    }

    /** Pod-wide: after a detected Git throttle/429, collapse every job's fan-out to wave size 1. */
    public void recordThrottle(String reason) {
        long cooldownMs = Math.max(0L, throttleCooldownSeconds) * 1000L;
        this.throttleCooldownUntilMs = System.currentTimeMillis() + cooldownMs;
        log.warn("Git throttle detected ({}); push-batch fan-out cooling down to wave size 1 for {}s",
                reason, throttleCooldownSeconds);
    }

    boolean hasResourceHeadroom() {
        double cpu = resourceMonitor.cpuLoad();
        if (cpu >= 0 && cpuHighWatermark > 0 && cpu > cpuHighWatermark) {
            return false;
        }
        double heap = resourceMonitor.heapUsedPercent();
        if (heap >= 0 && heapHighWatermarkPercent > 0 && heap > heapHighWatermarkPercent) {
            return false;
        }
        double diskFree = resourceMonitor.diskFreePercent();
        if (diskFree >= 0 && diskLowWatermarkPercent > 0 && diskFree < diskLowWatermarkPercent) {
            return false;
        }
        return true;
    }

    /** Pod-wide cap on simultaneous push connections to a single destination host. */
    public Semaphore hostSemaphore(String targetRepoUrl) {
        return hostPermits.computeIfAbsent(hostKey(targetRepoUrl),
                host -> new Semaphore(Math.max(1, perHostMax)));
    }

    public String hostKey(String targetRepoUrl) {
        try {
            URI uri = URI.create(targetRepoUrl);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase();
            }
        } catch (Exception ignored) {
            // fall through to raw key
        }
        return targetRepoUrl == null ? "unknown" : targetRepoUrl;
    }
}