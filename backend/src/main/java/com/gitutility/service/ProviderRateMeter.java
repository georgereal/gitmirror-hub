package com.gitutility.service;

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
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job-scoped REST vs Git-smart-HTTP counters. Bind {@link #bindJob} around {@code executeSync}
 * so Check Access from another tab does not inflate this job's count.
 * Process-level install usage is always recorded via {@link InstallApiUsageTracker}.
 * Accurate SCM API rate mapping is recorded via {@link ScmQuotaTracker}.
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

    private static final ThreadLocal<JobMeter> CURRENT = new ThreadLocal<>();
    private static final long ROLLING_WINDOW_MS = 60_000L;

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
        CURRENT.set(new JobMeter(jobId, Instant.now()));
    }

    public void unbindJob() {
        CURRENT.remove();
    }

    public void incrementGitHttpFetch() {
        JobMeter meter = CURRENT.get();
        if (meter != null) {
            meter.gitHttpFetchCount.incrementAndGet();
            meter.recordFetchTimestamp(System.currentTimeMillis());
        }
        recordGitToQuota(true, false, false);
    }

    public void incrementGitHttpPushBatch() {
        JobMeter meter = CURRENT.get();
        if (meter != null) {
            meter.gitHttpPushBatchCount.incrementAndGet();
            meter.recordPushTimestamp(System.currentTimeMillis());
        }
        recordGitToQuota(false, true, false);
    }

    public void recordGitHttpThrottle(String detail) {
        JobMeter meter = CURRENT.get();
        if (meter != null) {
            meter.gitHttpThrottleCount.incrementAndGet();
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
        JobMeter meter = CURRENT.get();
        if (meter == null) {
            return null;
        }
        return Math.max(0, Duration.between(meter.startedAt, Instant.now()).toMillis());
    }

    public Map<String, Object> snapshotMap() {
        JobMeter meter = CURRENT.get();
        if (meter == null) {
            return emptySnapshotMap();
        }
        return meter.toMap(gitPushPerMinuteGuideline);
    }

    public void copyTo(SyncJob job) {
        if (job == null) {
            return;
        }
        JobMeter meter = CURRENT.get();
        if (meter == null) {
            if (job.getRestCallCount() == null) {
                job.setRestCallCount(0);
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
        Map<String, Object> snap = meter.toMap(gitPushPerMinuteGuideline);
        job.setRestCallCount((Integer) snap.get("restCallCount"));
        job.setRestCallsPerMinute((Double) snap.get("restCallsPerMinute"));
        job.setGitHttpFetchCount((Integer) snap.get("gitHttpFetchCount"));
        job.setGitHttpPushBatchCount((Integer) snap.get("gitHttpPushBatchCount"));
        job.setGitPushPerMinute((Double) snap.get("gitPushPerMinute"));
        job.setGitFetchPerMinute((Double) snap.get("gitFetchPerMinute"));
        job.setGitHttpThrottleCount((Integer) snap.get("gitHttpThrottleCount"));
        job.setRateLimitRemaining((Integer) snap.get("rateLimitRemaining"));
        job.setRateLimitLimit((Integer) snap.get("rateLimitLimit"));
        job.setRateLimit429Count((Integer) snap.get("rateLimit429Count"));
        job.setProviderTrafficProvider((String) snap.get("provider"));
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
        JobMeter meter = CURRENT.get();

        String host = request.getURI() != null ? request.getURI().getHost() : null;
        String path = request.getURI() != null ? request.getURI().getPath() : null;
        boolean lfsProtocol = path != null && path.contains("/info/lfs");
        boolean gitTransferHost = lfsProtocol || (host != null && (host.contains("githubusercontent.com")
                || host.contains("s3.amazonaws.com")
                || host.contains("gitlab.com")
                || host.contains("bitbucket.org")));
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
            if (!gitTransferHost) {
                meter.restCallCount.incrementAndGet();
                if (provider != null) {
                    meter.provider = provider;
                }
            }
            if (remaining != null && !gitTransferHost) {
                meter.rateLimitRemaining = remaining;
            }
            if (limit != null && !gitTransferHost) {
                meter.rateLimitLimit = limit;
            }
            log.debug("[rate] job-{} {} {} -> {} remaining={}/{}",
                    meter.jobId, request.getMethod(), request.getURI(), code,
                    meter.rateLimitRemaining, meter.rateLimitLimit);

            if (gitTransferHost && (code == 429 || code == 403)) {
                meter.gitHttpThrottleCount.incrementAndGet();
            }
            if (rateLimited) {
                meter.rateLimit429Count.incrementAndGet();
            }
            boolean lowRemaining = meter.rateLimitLimit != null && meter.rateLimitLimit > 0
                    && meter.rateLimitRemaining != null
                    && meter.rateLimitRemaining * 5 < meter.rateLimitLimit;
            if (rateLimited || lowRemaining) {
                String warn = rateLimited
                        ? "Provider REST 429 Too Many Requests" + (host != null ? " from " + host : "")
                        : "Provider REST quota below 20% remaining (" + meter.rateLimitRemaining
                        + "/" + meter.rateLimitLimit + ")";
                log.warn("[rate] [job-{}] {}", meter.jobId, warn);
                persistWarnAudit(meter.jobId, warn);
            }
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

    private static Map<String, Object> emptySnapshotMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("restCallCount", 0);
        map.put("restCallsPerMinute", 0.0);
        map.put("gitHttpFetchCount", 0);
        map.put("gitHttpPushBatchCount", 0);
        map.put("gitPushPerMinute", 0.0);
        map.put("gitFetchPerMinute", 0.0);
        map.put("gitHttpThrottleCount", 0);
        map.put("gitPushRateGuideline", 6);
        map.put("rateLimitRemaining", null);
        map.put("rateLimitLimit", null);
        map.put("rateLimit429Count", 0);
        map.put("provider", null);
        return map;
    }

    private static final class JobMeter {
        final Long jobId;
        final Instant startedAt;
        final AtomicInteger restCallCount = new AtomicInteger();
        final AtomicInteger gitHttpFetchCount = new AtomicInteger();
        final AtomicInteger gitHttpPushBatchCount = new AtomicInteger();
        final AtomicInteger gitHttpThrottleCount = new AtomicInteger();
        final AtomicInteger rateLimit429Count = new AtomicInteger();
        final Deque<Long> pushTimestampsMs = new ArrayDeque<>();
        final Deque<Long> fetchTimestampsMs = new ArrayDeque<>();
        volatile Integer rateLimitRemaining;
        volatile Integer rateLimitLimit;
        volatile String provider;

        JobMeter(Long jobId, Instant startedAt) {
            this.jobId = jobId;
            this.startedAt = startedAt;
        }

        void recordPushTimestamp(long nowMs) {
            synchronized (pushTimestampsMs) {
                pushTimestampsMs.addLast(nowMs);
                pruneOlderThan(pushTimestampsMs, nowMs);
            }
        }

        void recordFetchTimestamp(long nowMs) {
            synchronized (fetchTimestampsMs) {
                fetchTimestampsMs.addLast(nowMs);
                pruneOlderThan(fetchTimestampsMs, nowMs);
            }
        }

        private static void pruneOlderThan(Deque<Long> deque, long nowMs) {
            while (!deque.isEmpty() && nowMs - deque.peekFirst() > ROLLING_WINDOW_MS) {
                deque.removeFirst();
            }
        }

        private static double perMinute(Deque<Long> deque, long nowMs) {
            synchronized (deque) {
                pruneOlderThan(deque, nowMs);
                return deque.size();
            }
        }

        Map<String, Object> toMap(int pushGuideline) {
            Map<String, Object> map = new LinkedHashMap<>();
            int rest = restCallCount.get();
            long elapsedMs = Math.max(1, Duration.between(startedAt, Instant.now()).toMillis());
            double restPerMin = rest * 60_000.0 / elapsedMs;
            long nowMs = System.currentTimeMillis();
            map.put("restCallCount", rest);
            map.put("restCallsPerMinute", Math.round(restPerMin * 10.0) / 10.0);
            map.put("gitHttpFetchCount", gitHttpFetchCount.get());
            map.put("gitHttpPushBatchCount", gitHttpPushBatchCount.get());
            map.put("gitPushPerMinute", Math.round(perMinute(pushTimestampsMs, nowMs) * 10.0) / 10.0);
            map.put("gitFetchPerMinute", Math.round(perMinute(fetchTimestampsMs, nowMs) * 10.0) / 10.0);
            map.put("gitHttpThrottleCount", gitHttpThrottleCount.get());
            map.put("gitPushRateGuideline", pushGuideline);
            map.put("rateLimitRemaining", rateLimitRemaining);
            map.put("rateLimitLimit", rateLimitLimit);
            map.put("rateLimit429Count", rateLimit429Count.get());
            map.put("provider", provider);
            return map;
        }
    }
}
