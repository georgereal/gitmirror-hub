package com.gitutility.service;

import lombok.extern.slf4j.Slf4j;
import com.gitutility.model.entity.InstanceHeartbeat;
import com.gitutility.repository.InstanceHeartbeatRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pod-wide push-batch concurrency governance. Each sync job gets a dynamic "wave size" —
 * how many of its own destination push batches may run in parallel — computed from a fair
 * share of the shared push-batch pool across concurrently-running jobs, live CPU/heap/disk
 * headroom ({@link SystemResourceMonitor}), and a pod-wide cooldown that collapses fan-out
 * after a detected Git throttle/429. The cooldown is one clock for every pair on this pod.
 * The first 429 in an episode sets a wait from live pod count and local job load; each later
 * 429 adds jitter up to a cap. A semaphore-based per-destination-host cap bounds
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

    @Value("${git-utility.git.push-batch-throttle-cooldown-max-seconds:1800}")
    private long throttleCooldownMaxSeconds;

    @Value("${git-utility.cluster.heartbeat-stale-seconds:15}")
    private int heartbeatStaleSeconds;

    @Value("${git-utility.git.push-batch-cpu-high-watermark:0.90}")
    private double cpuHighWatermark;

    @Value("${git-utility.git.push-batch-heap-high-watermark-percent:85}")
    private double heapHighWatermarkPercent;

    @Value("${git-utility.git.push-batch-disk-low-watermark-percent:10}")
    private double diskLowWatermarkPercent;

    private static final Pattern RETRY_AFTER = Pattern.compile("(?i)retry-after\\s*[:=]?\\s*(\\d+)");
    private static final long LIVE_POD_CACHE_MS = 5_000L;

    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Semaphore> hostPermits = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightBatches = new AtomicInteger();
    private final AtomicInteger inFlightPairs = new AtomicInteger();
    private volatile long throttleCooldownUntilMs = 0L;
    private volatile int cachedLivePods = 1;
    private volatile long livePodsCachedAtMs = 0L;

    private final SystemResourceMonitor resourceMonitor;
    private InstanceHeartbeatRepository heartbeatRepository;

    public PushBatchConcurrencyService(
            @Value("${git-utility.workspace-dir:/tmp/git-utility-mirrors}") String workspaceDir) {
        this.resourceMonitor = new SystemResourceMonitor(workspaceDir);
    }

    @Autowired(required = false)
    public void setHeartbeatRepository(InstanceHeartbeatRepository heartbeatRepository) {
        this.heartbeatRepository = heartbeatRepository;
    }

    /** Counts a pair for the whole sync, including metadata and LFS, not only the push wave. */
    public void pairBegin(String jobId) {
        if (jobId != null) {
            inFlightPairs.incrementAndGet();
        }
    }

    public void pairEnd(String jobId) {
        if (jobId != null) {
            inFlightPairs.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    /** Marks a sync job as being in its push phase (fair-share wave sizing). */
    public void jobBegin(String jobId) {
        if (jobId != null) {
            activeJobs.add(jobId);
        }
    }

    /** Marks a sync job as done with its push phase. */
    public void jobEnd(String jobId) {
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
    public int waveSize(String jobId) {
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

    /**
     * Pod-wide rate-limit episode. The first 429 sets
     * {@code floor × live pods × local pairs} plus one jitter slice. Each later 429 before the
     * deadline adds another {@code 0 .. floor} jitter slice and does not restart the base.
     * The deadline never sits further than {@code GIT_PUSH_BATCH_THROTTLE_COOLDOWN_MAX_SECONDS} from now.
     */
    public synchronized void recordThrottle(String reason) {
        long now = System.currentTimeMillis();
        long floorMs = Math.max(0L, throttleCooldownSeconds) * 1000L;
        long capMs = (throttleCooldownMaxSeconds > 0 ? throttleCooldownMaxSeconds : 1800L) * 1000L;
        long jitterMs = nextJitterMs(floorMs);
        boolean episode = now < throttleCooldownUntilMs;
        long deadline;
        if (!episode) {
            int pods = livePodCount();
            int jobs = localPairCount();
            deadline = now + (floorMs * pods * jobs) + jitterMs;
        } else {
            deadline = throttleCooldownUntilMs + jitterMs;
        }
        Long retryAfterSeconds = retryAfterSeconds(reason);
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            deadline = Math.max(deadline, now + retryAfterSeconds * 1000L);
        }
        deadline = Math.min(deadline, now + capMs);
        if (episode) {
            deadline = Math.max(throttleCooldownUntilMs, deadline);
            deadline = Math.min(deadline, now + capMs);
        }
        this.throttleCooldownUntilMs = deadline;
        long waitSeconds = Math.max(0L, (deadline - now) / 1000L);
        log.warn("Git throttle detected ({}); pod-wide cooldown for {}s ({})",
                reason, waitSeconds, episode ? "extended by a later 429" : "started");
    }

    /** Blocks the caller while the pod-wide cooldown is active. Later 429s extend the wait. */
    public void awaitClear() throws InterruptedException {
        while (isThrottleCooldownActive()) {
            long remain = throttleCooldownRemainingMs();
            if (remain <= 0) {
                return;
            }
            Thread.sleep(Math.min(remain, 1000L));
        }
    }

    /** Starts or extends the cooldown when the failure is an HTTP 429 or secondary rate limit. */
    public void noteIfRateLimited(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (ProviderRateMeter.looksLikeGitThrottle(current.getMessage())) {
                recordThrottle(current.getMessage());
                return;
            }
            current = current.getCause();
        }
    }

    int localPairCount() {
        return Math.max(1, Math.max(inFlightPairs.get(), activeJobs.size()));
    }

    int livePodCount() {
        long now = System.currentTimeMillis();
        if (now - livePodsCachedAtMs < LIVE_POD_CACHE_MS && cachedLivePods >= 1) {
            return cachedLivePods;
        }
        int live = 1;
        if (heartbeatRepository != null) {
            try {
                Instant staleBefore = Instant.now().minus(Math.max(5, heartbeatStaleSeconds), ChronoUnit.SECONDS);
                int fresh = 0;
                for (InstanceHeartbeat row : heartbeatRepository.findAll()) {
                    if (row.getUpdatedAt() != null && !row.getUpdatedAt().isBefore(staleBefore)) {
                        fresh++;
                    }
                }
                live = Math.max(1, fresh);
            } catch (Exception e) {
                log.debug("Live pod count unavailable, using {}: {}", cachedLivePods, e.getMessage());
                live = Math.max(1, cachedLivePods);
            }
        }
        cachedLivePods = live;
        livePodsCachedAtMs = now;
        return live;
    }

    /** {@code 0 .. floorMs} inclusive. Package-visible so tests can pin it. */
    long nextJitterMs(long floorMs) {
        if (floorMs <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(floorMs + 1);
    }

    static Long retryAfterSeconds(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = RETRY_AFTER.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
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