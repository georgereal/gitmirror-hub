package com.gitutility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.LogLevel;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job-scoped REST / GraphQL / LFS / Git-smart-HTTP counters for the whole run.
 * {@link #bindJob} registers a durable meter; worker threads call {@link #attachJob}
 * so PR-create and LFS transfer pools attribute HTTP to the same run totals.
 * Process-level install usage is always recorded via {@link InstallApiUsageTracker}.
 */
@Component
@Slf4j
public class ProviderRateMeter implements ClientHttpRequestInterceptor {

    private final SyncAuditLogRepository auditLogRepository;
    private final EnterpriseLoggingService enterpriseLoggingService;
    private final InstallApiUsageTracker installApiUsageTracker;
    private final GitHubAppConfigRepository configRepository;
    private final ScmQuotaTracker scmQuotaTracker;
    private final ScmInstallationKeyResolver installationKeyResolver;

    @Value("${git-utility.provider-limits.github.push-per-minute-guideline:6}")
    private int gitPushPerMinuteGuideline;

    private static final ConcurrentHashMap<Long, JobMeter> METERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> CURRENT_JOB_ID = new ThreadLocal<>();
    private static final long ROLLING_WINDOW_MS = 60_000L;
    private static final long SAMPLE_INTERVAL_MS = 2_000L;
    private static final int MAX_SAMPLES = 180;
    private static final ObjectMapper SERIES_MAPPER = new ObjectMapper();

    public ProviderRateMeter(SyncAuditLogRepository auditLogRepository,
                             @Lazy EnterpriseLoggingService enterpriseLoggingService,
                             InstallApiUsageTracker installApiUsageTracker,
                             GitHubAppConfigRepository configRepository,
                             ScmQuotaTracker scmQuotaTracker,
                             ScmInstallationKeyResolver installationKeyResolver) {
        this.auditLogRepository = auditLogRepository;
        this.enterpriseLoggingService = enterpriseLoggingService;
        this.installApiUsageTracker = installApiUsageTracker;
        this.configRepository = configRepository;
        this.scmQuotaTracker = scmQuotaTracker;
        this.installationKeyResolver = installationKeyResolver;
    }

    public void bindJob(Long jobId) {
        if (jobId == null) {
            return;
        }
        METERS.put(jobId, new JobMeter(jobId, Instant.now()));
        CURRENT_JOB_ID.set(jobId);
    }

    /**
     * Attach this thread to an already-bound job (LFS/PR worker pools).
     * No-op if the job meter was never bound or already unbound.
     */
    public void attachJob(Long jobId) {
        if (jobId == null || !METERS.containsKey(jobId)) {
            return;
        }
        CURRENT_JOB_ID.set(jobId);
    }

    public void detachJob() {
        CURRENT_JOB_ID.remove();
    }

    public void unbindJob() {
        Long id = CURRENT_JOB_ID.get();
        CURRENT_JOB_ID.remove();
        if (id != null) {
            METERS.remove(id);
        }
    }

    private JobMeter current() {
        Long id = CURRENT_JOB_ID.get();
        return id != null ? METERS.get(id) : null;
    }

    private JobMeter meterFor(SyncJob job) {
        JobMeter meter = current();
        if (meter != null) {
            return meter;
        }
        if (job != null && job.getId() != null) {
            return METERS.get(job.getId());
        }
        return null;
    }

    public void incrementGitHttpFetch() {
        JobMeter meter = current();
        if (meter != null) {
            meter.gitHttpFetchCount.incrementAndGet();
            meter.recordFetchTimestamp(System.currentTimeMillis());
            meter.maybeSample(false);
        }
        recordGitToQuota(true, false, false);
    }

    public void incrementGitHttpPushBatch() {
        JobMeter meter = current();
        if (meter != null) {
            meter.gitHttpPushBatchCount.incrementAndGet();
            meter.recordPushTimestamp(System.currentTimeMillis());
            meter.maybeSample(false);
        }
        recordGitToQuota(false, true, false);
    }

    public void recordGitHttpThrottle(String detail) {
        JobMeter meter = current();
        if (meter != null) {
            meter.gitHttpThrottleCount.incrementAndGet();
            meter.maybeSample(false);
            log.warn("[rate] [job-{}] Git protocol throttle: {}", meter.jobId, detail);
        }
        recordGitToQuota(false, false, true);
    }

    private void recordGitToQuota(boolean fetch, boolean push, boolean throttled) {
        if (scmQuotaTracker == null) {
            return;
        }
        ScmQuotaContext.Bound ctx = ScmQuotaContext.current();
        String provider = ctx != null && ctx.provider() != null ? ctx.provider() : "github";
        String repo = ctx != null ? ctx.repoFullName() : null;
        if (repo == null || repo.isBlank()) {
            return;
        }
        scmQuotaTracker.recordGitTraffic(provider, repo, fetch, push, throttled);
    }

    public Long wallElapsedMs() {
        JobMeter meter = current();
        if (meter == null) {
            return null;
        }
        return Math.max(0, Duration.between(meter.startedAt, Instant.now()).toMillis());
    }

    /**
     * Live run totals for WebSocket progress. Returns {@code null} when this thread
     * is not attached to a bound job so callers omit traffic instead of broadcasting zeros.
     */
    public Map<String, Object> snapshotMap() {
        JobMeter meter = current();
        if (meter == null) {
            return null;
        }
        meter.maybeSample(true);
        return meter.toMap(gitPushPerMinuteGuideline);
    }

    public void recordGraphqlPoints(Integer cost) {
        JobMeter meter = current();
        if (meter == null) {
            return;
        }
        if (cost != null && cost > 0) {
            meter.graphqlPointsUsed.addAndGet(cost);
        }
        meter.maybeSample(false);
    }

    public void recordTransferBytes(long gitReadBytes, long gitWriteBytes, long lfsBytes) {
        JobMeter meter = current();
        if (meter == null) {
            return;
        }
        if (gitReadBytes > meter.gitReadBytes) {
            meter.gitReadBytes = gitReadBytes;
        }
        if (gitWriteBytes > meter.gitWriteBytes) {
            meter.gitWriteBytes = gitWriteBytes;
        }
        if (lfsBytes > meter.lfsBytes) {
            meter.lfsBytes = lfsBytes;
        }
        meter.maybeSample(false);
    }

    public void copyTo(SyncJob job) {
        if (job == null) {
            return;
        }
        JobMeter meter = meterFor(job);
        if (meter == null) {
            if (job.getRestCallCount() == null) {
                job.setRestCallCount(0);
            }
            if (job.getGraphqlCallCount() == null) {
                job.setGraphqlCallCount(0);
            }
            if (job.getLfsApiCallCount() == null) {
                job.setLfsApiCallCount(0);
            }
            if (job.getLfsTransferHttpCount() == null) {
                job.setLfsTransferHttpCount(0);
            }
            if (job.getGitHttpFetchCount() == null) {
                job.setGitHttpFetchCount(0);
            }
            if (job.getGitHttpPushBatchCount() == null) {
                job.setGitHttpPushBatchCount(0);
            }
            if (job.getGitHttpThrottleCount() == null) {
                job.setGitHttpThrottleCount(0);
            }
            return;
        }
        meter.maybeSample(true);
        Map<String, Object> snap = meter.toMap(gitPushPerMinuteGuideline);
        job.setRestCallCount((Integer) snap.get("restCallCount"));
        job.setRestCallsPerMinute((Double) snap.get("restCallsPerMinute"));
        job.setGraphqlCallCount((Integer) snap.get("graphqlCallCount"));
        job.setGraphqlPointsUsed((Integer) snap.get("graphqlPointsUsed"));
        job.setGraphql429Count((Integer) snap.get("graphql429Count"));
        job.setLfsApiCallCount((Integer) snap.get("lfsApiCallCount"));
        job.setLfsTransferHttpCount((Integer) snap.get("lfsTransferHttpCount"));
        job.setGitHttpFetchCount((Integer) snap.get("gitHttpFetchCount"));
        job.setGitHttpPushBatchCount((Integer) snap.get("gitHttpPushBatchCount"));
        job.setGitPushPerMinute((Double) snap.get("gitPushPerMinute"));
        job.setGitFetchPerMinute((Double) snap.get("gitFetchPerMinute"));
        job.setGitHttpThrottleCount((Integer) snap.get("gitHttpThrottleCount"));
        job.setRateLimitRemaining((Integer) snap.get("rateLimitRemaining"));
        job.setRateLimitLimit((Integer) snap.get("rateLimitLimit"));
        job.setRateLimit429Count((Integer) snap.get("rateLimit429Count"));
        job.setProviderTrafficProvider((String) snap.get("provider"));
        job.setGitReadBytes((Long) snap.get("gitReadBytes"));
        job.setGitWriteBytes((Long) snap.get("gitWriteBytes"));
        Object lfsSnap = snap.get("lfsBytes");
        if (lfsSnap instanceof Number lfsNum && lfsNum.longValue() > 0) {
            job.setLfsBytes(lfsNum.longValue());
        }
        Object series = snap.get("series");
        if (series != null) {
            try {
                job.setProviderTrafficSeriesJson(SERIES_MAPPER.writeValueAsString(series));
            } catch (Exception ignored) {
            }
        }
    }

    static boolean looksLikeGitThrottle(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("429")
                || lower.contains("rate limit")
                || lower.contains("too many requests")
                || lower.contains("throttl")
                || (lower.contains("403") && lower.contains("access denied"));
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        JobMeter meter = current();

        String host = request.getURI() != null ? request.getURI().getHost() : null;
        String path = request.getURI() != null ? request.getURI().getPath() : null;
        boolean lfsProtocol = path != null && path.contains("/info/lfs");
        boolean lfsMediaHost = host != null && (host.contains("githubusercontent.com")
                || host.contains("s3.amazonaws.com")
                || host.contains("gitlab.com")
                || host.contains("bitbucket.org")
                || host.contains("blob.core.windows.net"));
        boolean gitTransferHost = lfsProtocol || lfsMediaHost;
        boolean graphql = path != null && path.contains("graphql");
        boolean scmRestApi = looksLikeScmRestApi(host, path);

        String provider = inferProvider(host);
        ScmQuotaContext.Bound ctx = ScmQuotaContext.current();
        if (provider == null && ctx != null) {
            provider = ctx.provider();
        }
        String installationKey = ctx != null && ctx.installationKey() != null
                ? ctx.installationKey()
                : (installationKeyResolver != null ? installationKeyResolver.resolve(provider) : "default");
        String repoFromPath = parseRepoFullName(path);
        String repo = ctx != null && ctx.repoFullName() != null ? ctx.repoFullName() : repoFromPath;

        HttpHeaders headers = response.getHeaders();
        Integer remaining = firstRateLimitInt(headers, "remaining");
        Integer limit = firstRateLimitInt(headers, "limit");
        Instant resetAt = parseReset(headers);

        HttpStatusCode status = response.getStatusCode();
        int code = status.value();
        boolean rateLimited = code == 429 && !gitTransferHost;

        if (!gitTransferHost && installApiUsageTracker != null) {
            InstallRef install = resolveInstall(host, provider);
            if (install != null) {
                installApiUsageTracker.recordRestCall(
                        install.key(), install.provider(), install.label(),
                        remaining, limit, rateLimited);
            }
        }

        if (meter != null) {
            if (graphql) {
                meter.graphqlCallCount.incrementAndGet();
                if (provider != null) {
                    meter.provider = provider;
                }
            } else if (lfsProtocol) {
                meter.lfsApiCallCount.incrementAndGet();
                if (provider != null) {
                    meter.provider = provider;
                }
            } else if (lfsMediaHost) {
                meter.lfsTransferHttpCount.incrementAndGet();
                if (provider != null) {
                    meter.provider = provider;
                }
            } else if (!gitTransferHost) {
                meter.restCallCount.incrementAndGet();
                if (provider != null) {
                    meter.provider = provider;
                }
            }
            if (remaining != null && !gitTransferHost && !graphql) {
                meter.rateLimitRemaining = remaining;
            }
            if (limit != null && !gitTransferHost && !graphql) {
                meter.rateLimitLimit = limit;
            }
            log.debug("[rate] job-{} {} {} -> {} remaining={}/{}",
                    meter.jobId, request.getMethod(), request.getURI(), code,
                    meter.rateLimitRemaining, meter.rateLimitLimit);

            if (gitTransferHost && (code == 429 || code == 403)) {
                meter.gitHttpThrottleCount.incrementAndGet();
            }
            if (code == 429 && graphql) {
                meter.graphql429Count.incrementAndGet();
            } else if (code == 429 && !gitTransferHost) {
                meter.rateLimit429Count.incrementAndGet();
            }
            boolean restRateLimited = code == 429 && !gitTransferHost && !graphql;
            boolean lowRemaining = !graphql && meter.rateLimitLimit != null && meter.rateLimitLimit > 0
                    && meter.rateLimitRemaining != null
                    && meter.rateLimitRemaining * 5 < meter.rateLimitLimit;
            if (restRateLimited || lowRemaining) {
                String warn = restRateLimited
                        ? "Provider REST 429 Too Many Requests" + (host != null ? " from " + host : "")
                        : "Provider REST quota below 20% remaining (" + meter.rateLimitRemaining
                        + "/" + meter.rateLimitLimit + ")";
                log.warn("[rate] [job-{}] {}", meter.jobId, warn);
                persistWarnAudit(meter.jobId, warn);
            }
            meter.maybeSample(false);
        }

        // GraphQL quotas (incl. body rateLimit.cost) are recorded in GithubGraphQlClient to avoid double-count.
        // Only count primary REST quota for real API hosts (or when rate-limit headers are present).
        // LFS / raw / media hosts often have no X-RateLimit-* and were showing blank "REST remaining".
        if (scmQuotaTracker != null && provider != null && !gitTransferHost && !graphql
                && (scmRestApi || remaining != null || limit != null)) {
            scmQuotaTracker.recordRestQuota(provider, installationKey, remaining, limit, resetAt, code == 429);
        } else if (scmQuotaTracker != null && provider != null && !gitTransferHost && !graphql
                && remaining == null && log.isDebugEnabled()) {
            log.debug("[rate] skip REST quota record (no rate-limit headers) {} {}", request.getMethod(), request.getURI());
        }
        if (scmQuotaTracker != null && gitTransferHost && repo != null && (code == 429 || code == 403)) {
            scmQuotaTracker.recordGitTraffic(provider != null ? provider : "github", repo, false, false, true);
        }
        return response;
    }


    private InstallRef resolveInstall(String host, String provider) {
        if (configRepository == null) {
            if (provider != null) {
                return new InstallRef(provider + ":unknown", provider, provider);
            }
            return null;
        }
        GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
        if (config == null) {
            if (provider != null) {
                return new InstallRef(provider + ":unknown", provider, provider);
            }
            return null;
        }

        String ghesHost = hostOf(config.getGhesHostUrl());
        boolean isGhes = ghesHost != null && host != null
                && (host.equalsIgnoreCase(ghesHost) || host.endsWith("." + ghesHost));
        boolean isGithubCloud = host != null && host.toLowerCase().contains("github") && !isGhes;

        if (isGhes || "ghes".equals(provider)) {
            String installId = blankToNull(config.getGhesInstallationId());
            if (installId != null) {
                String label = blankToNull(config.getGhesHostUrl()) != null
                        ? "GHES App install " + installId + " @ " + config.getGhesHostUrl()
                        : "GHES App install " + installId;
                return new InstallRef("ghes:" + installId, "ghes", label);
            }
            if ("GITHUB_APP".equalsIgnoreCase(config.getGhesAuthType())) {
                return new InstallRef("ghes:app-unbound", "ghes", "GHES App (no install id)");
            }
            return new InstallRef("ghes:pat", "ghes", "GHES PAT");
        }

        if (isGithubCloud || "github".equals(provider)) {
            String installId = blankToNull(config.getInstallationId());
            if (installId != null) {
                return new InstallRef("github:" + installId, "github", "GitHub App install " + installId);
            }
            if ("GITHUB_APP".equalsIgnoreCase(config.getAuthType())) {
                return new InstallRef("github:app-unbound", "github", "GitHub App (no install id)");
            }
            return new InstallRef("github:pat", "github", "GitHub PAT");
        }

        if (provider != null) {
            return new InstallRef(provider + ":default", provider, provider);
        }
        return null;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String cleaned = url.trim();
            if (!cleaned.contains("://")) {
                cleaned = "https://" + cleaned;
            }
            return java.net.URI.create(cleaned).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record InstallRef(String key, String provider, String label) {}

    private static Instant parseReset(HttpHeaders headers) {
        Integer epoch = firstRateLimitInt(headers, "reset");
        if (epoch == null || epoch <= 0) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(epoch);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * api.github.com / GHES /api/v3 / GitLab / Bitbucket API hosts — not github.com LFS or HTML.
     */
    static boolean looksLikeScmRestApi(String host, String path) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        if (h.equals("api.github.com") || h.startsWith("api.") && h.contains("github")) {
            return true;
        }
        if (path != null) {
            String p = path.toLowerCase();
            if (p.startsWith("/api/v3") || p.startsWith("/api/v4") || p.contains("/rest/api/")) {
                return true;
            }
        }
        if (h.contains("gitlab") && path != null && path.contains("/api/")) {
            return true;
        }
        if (h.contains("bitbucket") && path != null && (path.contains("/2.0/") || path.contains("/api/"))) {
            return true;
        }
        return false;
    }

    /**
     * Case-insensitive scan for GitHub-style rate limit headers (X-RateLimit-*, RateLimit-*).
     */
    static Integer firstRateLimitInt(HttpHeaders headers, String field) {
        if (headers == null || field == null) {
            return null;
        }
        String needle = "ratelimit-" + field.toLowerCase();
        String alt = "rate-limit-" + field.toLowerCase();
        for (String name : headers.keySet()) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase();
            if (lower.contains(needle) || lower.contains(alt)) {
                String raw = headers.getFirst(name);
                if (raw != null && !raw.isBlank()) {
                    try {
                        return Integer.parseInt(raw.trim().split(",")[0].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        // Explicit common names (covers odd maps that don't iterate as expected)
        return firstIntHeader(headers,
                "X-RateLimit-" + capitalize(field),
                "RateLimit-" + capitalize(field),
                "X-Rate-Limit-" + capitalize(field));
    }

    private static String capitalize(String field) {
        if (field == null || field.isEmpty()) {
            return field;
        }
        return Character.toUpperCase(field.charAt(0)) + field.substring(1).toLowerCase();
    }

    static String parseRepoFullName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        // /repos/{owner}/{repo}/...
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 2; i++) {
            if ("repos".equals(parts[i]) && !parts[i + 1].isBlank() && !parts[i + 2].isBlank()) {
                String owner = parts[i + 1];
                String repo = parts[i + 2];
                if (repo.contains(".")) {
                    // unlikely; keep as-is
                }
                return owner + "/" + repo;
            }
        }
        return null;
    }

    private void persistWarnAudit(Long jobId, String message) {
        if (jobId == null || auditLogRepository == null) {
            return;
        }
        try {
            SyncAuditLog entry = SyncAuditLog.builder()
                    .jobId(jobId)
                    .level(LogLevel.WARN)
                    .message(message)
                    .timestamp(java.time.Instant.now())
                    .build();
            auditLogRepository.save(entry);
            if (enterpriseLoggingService != null) {
                enterpriseLoggingService.shipAuditLog(entry);
            }
        } catch (Exception e) {
            log.debug("Could not persist rate-limit audit: {}", e.getMessage());
        }
    }

    static String inferProvider(String host) {
        if (host == null) {
            return null;
        }
        String h = host.toLowerCase();
        if (h.contains("github")) {
            return "github";
        }
        if (h.contains("gitlab")) {
            return "gitlab";
        }
        if (h.contains("bitbucket")) {
            return "bitbucket";
        }
        if (h.contains("origin.cursor")) {
            return "origin";
        }
        return h;
    }

    private static Integer firstIntHeader(HttpHeaders headers, String... names) {
        if (headers == null) {
            return null;
        }
        for (String name : names) {
            String raw = headers.getFirst(name);
            if (raw != null && !raw.isBlank()) {
                try {
                    return Integer.parseInt(raw.trim().split(",")[0].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class JobMeter {
        final Long jobId;
        final Instant startedAt;
        final AtomicInteger restCallCount = new AtomicInteger();
        final AtomicInteger graphqlCallCount = new AtomicInteger();
        final AtomicInteger graphqlPointsUsed = new AtomicInteger();
        final AtomicInteger graphql429Count = new AtomicInteger();
        final AtomicInteger lfsApiCallCount = new AtomicInteger();
        final AtomicInteger lfsTransferHttpCount = new AtomicInteger();
        final AtomicInteger gitHttpFetchCount = new AtomicInteger();
        final AtomicInteger gitHttpPushBatchCount = new AtomicInteger();
        final AtomicInteger gitHttpThrottleCount = new AtomicInteger();
        final AtomicInteger rateLimit429Count = new AtomicInteger();
        final Deque<Long> pushTimestampsMs = new ArrayDeque<>();
        final Deque<Long> fetchTimestampsMs = new ArrayDeque<>();
        final List<Map<String, Object>> samples = new ArrayList<>();
        volatile Integer rateLimitRemaining;
        volatile Integer rateLimitLimit;
        volatile String provider;
        volatile long gitReadBytes;
        volatile long gitWriteBytes;
        volatile long lfsBytes;
        volatile double gitPushPerMinutePeak;
        volatile double gitFetchPerMinutePeak;
        volatile long lastSampleAtMs;

        JobMeter(Long jobId, Instant startedAt) {
            this.jobId = jobId;
            this.startedAt = startedAt;
        }

        void recordPushTimestamp(long nowMs) {
            synchronized (pushTimestampsMs) {
                pushTimestampsMs.addLast(nowMs);
                pruneOlderThan(pushTimestampsMs, nowMs);
                gitPushPerMinutePeak = Math.max(gitPushPerMinutePeak, pushTimestampsMs.size());
            }
        }

        void recordFetchTimestamp(long nowMs) {
            synchronized (fetchTimestampsMs) {
                fetchTimestampsMs.addLast(nowMs);
                pruneOlderThan(fetchTimestampsMs, nowMs);
                gitFetchPerMinutePeak = Math.max(gitFetchPerMinutePeak, fetchTimestampsMs.size());
            }
        }

        private static void pruneOlderThan(Deque<Long> deque, long nowMs) {
            while (!deque.isEmpty() && nowMs - deque.peekFirst() > ROLLING_WINDOW_MS) {
                deque.removeFirst();
            }
        }

        synchronized void maybeSample(boolean force) {
            long nowMs = System.currentTimeMillis();
            if (!force && lastSampleAtMs > 0 && nowMs - lastSampleAtMs < SAMPLE_INTERVAL_MS) {
                return;
            }
            lastSampleAtMs = nowMs;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", Math.max(0L, nowMs - startedAt.toEpochMilli()));
            point.put("rest", restCallCount.get());
            point.put("graphql", graphqlCallCount.get());
            point.put("graphqlPoints", graphqlPointsUsed.get());
            point.put("lfsApi", lfsApiCallCount.get());
            point.put("lfsHttp", lfsTransferHttpCount.get());
            point.put("gitFetch", gitHttpFetchCount.get());
            point.put("gitPush", gitHttpPushBatchCount.get());
            point.put("gitReadBytes", gitReadBytes);
            point.put("gitWriteBytes", gitWriteBytes);
            point.put("lfsBytes", lfsBytes);
            samples.add(point);
            if (samples.size() > MAX_SAMPLES) {
                downsampleEvenly(MAX_SAMPLES);
            }
        }

        /** Keep first/last and evenly spaced midpoints so X covers the full job duration. */
        private void downsampleEvenly(int keep) {
            if (samples.size() <= keep || keep < 2) {
                return;
            }
            List<Map<String, Object>> compacted = new ArrayList<>(keep);
            int n = samples.size();
            for (int i = 0; i < keep; i++) {
                int idx = (int) Math.round(i * (n - 1) / (double) (keep - 1));
                compacted.add(samples.get(idx));
            }
            samples.clear();
            samples.addAll(compacted);
        }

        Map<String, Object> toMap(int pushGuideline) {
            Map<String, Object> map = new LinkedHashMap<>();
            int rest = restCallCount.get();
            int gql = graphqlCallCount.get();
            int lfsApi = lfsApiCallCount.get();
            int lfsHttp = lfsTransferHttpCount.get();
            int fetches = gitHttpFetchCount.get();
            int pushes = gitHttpPushBatchCount.get();
            long elapsedMs = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            map.put("restCallCount", rest);
            map.put("restCallsPerMinute", round1(rest * 60_000.0 / elapsedMs));
            map.put("graphqlCallCount", gql);
            map.put("graphqlPointsUsed", graphqlPointsUsed.get());
            map.put("graphql429Count", graphql429Count.get());
            map.put("lfsApiCallCount", lfsApi);
            map.put("lfsTransferHttpCount", lfsHttp);
            map.put("gitHttpFetchCount", fetches);
            map.put("gitHttpPushBatchCount", pushes);
            map.put("gitPushPerMinute", round1(pushes * 60_000.0 / elapsedMs));
            map.put("gitFetchPerMinute", round1(fetches * 60_000.0 / elapsedMs));
            map.put("gitPushPerMinutePeak", round1(gitPushPerMinutePeak));
            map.put("gitFetchPerMinutePeak", round1(gitFetchPerMinutePeak));
            map.put("gitHttpThrottleCount", gitHttpThrottleCount.get());
            map.put("gitPushRateGuideline", pushGuideline);
            map.put("rateLimitRemaining", rateLimitRemaining);
            map.put("rateLimitLimit", rateLimitLimit);
            map.put("rateLimit429Count", rateLimit429Count.get());
            map.put("gitReadBytes", gitReadBytes);
            map.put("gitWriteBytes", gitWriteBytes);
            map.put("lfsBytes", lfsBytes);
            map.put("provider", provider);
            Map<String, Object> series = new LinkedHashMap<>();
            series.put("samples", new ArrayList<>(samples));
            series.put("gitPushPerMinutePeak", round1(gitPushPerMinutePeak));
            series.put("gitFetchPerMinutePeak", round1(gitFetchPerMinutePeak));
            map.put("series", series);
            return map;
        }
    }
}
