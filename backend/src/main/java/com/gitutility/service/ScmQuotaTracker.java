package com.gitutility.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide SCM quota / traffic observability.
 * <ul>
 *   <li>App / token quotas keyed by {@code provider + installationKey} (low cardinality for Micrometer)</li>
 *   <li>Per-repo Git / GraphQL hotspots kept in-memory (top-N for UI; not Micrometer-tagged by repo)</li>
 * </ul>
 */
@Component
public class ScmQuotaTracker {

    public static final String RESOURCE_REST = "rest";
    public static final String RESOURCE_GRAPHQL = "graphql";
    public static final String RESOURCE_GIT = "git";

    private final MeterRegistry meterRegistry;
    private final Map<String, InstallQuota> installs = new ConcurrentHashMap<>();
    private final Map<String, RepoHotspot> repos = new ConcurrentHashMap<>();

    private final AtomicLong micrometerInstallRemaining = new AtomicLong(-1);
    private final AtomicLong micrometerInstallLimit = new AtomicLong(-1);
    private final AtomicLong micrometerGraphqlRemaining = new AtomicLong(-1);
    private final AtomicLong micrometerRest429 = new AtomicLong();
    private final AtomicLong micrometerGitThrottles = new AtomicLong();

    public ScmQuotaTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bindGauges() {
        Gauge.builder("gitmirror.scm.quota.remaining", micrometerInstallRemaining, AtomicLong::get)
                .description("Latest observed REST primary remaining (any install)")
                .tag("resource", RESOURCE_REST)
                .register(meterRegistry);
        Gauge.builder("gitmirror.scm.quota.limit", micrometerInstallLimit, AtomicLong::get)
                .description("Latest observed REST primary limit")
                .tag("resource", RESOURCE_REST)
                .register(meterRegistry);
        Gauge.builder("gitmirror.scm.quota.remaining", micrometerGraphqlRemaining, AtomicLong::get)
                .description("Latest observed GraphQL points remaining")
                .tag("resource", RESOURCE_GRAPHQL)
                .register(meterRegistry);
        Gauge.builder("gitmirror.scm.quota.rate_limited_total", micrometerRest429, AtomicLong::get)
                .tag("resource", RESOURCE_REST)
                .register(meterRegistry);
        Gauge.builder("gitmirror.scm.quota.git_throttles_total", micrometerGitThrottles, AtomicLong::get)
                .tag("resource", RESOURCE_GIT)
                .register(meterRegistry);
    }

    public void recordRestQuota(String provider,
                                String installationKey,
                                Integer remaining,
                                Integer limit,
                                Instant resetAt,
                                boolean rateLimited) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        String key = installKey(provider, installationKey);
        InstallQuota q = installs.computeIfAbsent(key, k -> InstallQuota.create(provider, installationKey));
        synchronized (q) {
            q.restCalls++;
            if (remaining != null) {
                q.restRemaining = remaining;
                micrometerInstallRemaining.set(remaining);
            }
            if (limit != null) {
                q.restLimit = limit;
                micrometerInstallLimit.set(limit);
            }
            if (resetAt != null) {
                q.restResetAt = resetAt;
            }
            q.updatedAt = Instant.now();
            if (rateLimited) {
                q.rest429Count++;
                micrometerRest429.incrementAndGet();
            }
            q.maybeSample();
        }
    }

    public void recordGraphqlQuota(String provider,
                                   String installationKey,
                                   String repoFullName,
                                   Integer remaining,
                                   Integer limit,
                                   Integer cost,
                                   boolean rateLimited) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        String key = installKey(provider, installationKey);
        InstallQuota q = installs.computeIfAbsent(key, k -> InstallQuota.create(provider, installationKey));
        synchronized (q) {
            q.graphqlCalls++;
            if (remaining != null) {
                q.graphqlRemaining = remaining;
                micrometerGraphqlRemaining.set(remaining);
            }
            if (limit != null) {
                q.graphqlLimit = limit;
            }
            if (cost != null) {
                q.graphqlPointsUsed += cost;
            }
            q.updatedAt = Instant.now();
            if (rateLimited) {
                q.graphql429Count++;
            }
            q.maybeSample();
        }
        if (repoFullName != null && !repoFullName.isBlank()) {
            RepoHotspot hot = repos.computeIfAbsent(repoKey(provider, repoFullName),
                    k -> RepoHotspot.create(provider, repoFullName));
            synchronized (hot) {
                hot.graphqlCalls++;
                if (cost != null) {
                    hot.graphqlPointsUsed += cost;
                }
                if (rateLimited) {
                    hot.graphql429Count++;
                }
                hot.updatedAt = Instant.now();
            }
        }
    }

    public void recordGitTraffic(String provider,
                                 String repoFullName,
                                 boolean fetch,
                                 boolean push,
                                 boolean throttled) {
        if (provider == null || provider.isBlank() || repoFullName == null || repoFullName.isBlank()) {
            return;
        }
        RepoHotspot hot = repos.computeIfAbsent(repoKey(provider, repoFullName),
                k -> RepoHotspot.create(provider, repoFullName));
        synchronized (hot) {
            if (fetch) {
                hot.gitFetches++;
            }
            if (push) {
                hot.gitPushes++;
            }
            if (throttled) {
                hot.gitThrottles++;
                micrometerGitThrottles.incrementAndGet();
            }
            hot.updatedAt = Instant.now();
        }
    }

    public Snapshot snapshot(int topRepos) {
        List<InstallQuotaView> installViews = new ArrayList<>();
        for (InstallQuota q : installs.values()) {
            synchronized (q) {
                List<QuotaSampleView> series = q.copySeries();
                ExternalUsageHint hint = computeExternalHint(series);
                installViews.add(InstallQuotaView.builder()
                        .provider(q.provider)
                        .installationKey(q.installationKey)
                        .restRemaining(q.restRemaining >= 0 ? q.restRemaining : null)
                        .restLimit(q.restLimit >= 0 ? q.restLimit : null)
                        .restResetAt(q.restResetAt)
                        .restCalls(q.restCalls)
                        .rest429Count(q.rest429Count)
                        .graphqlRemaining(q.graphqlRemaining >= 0 ? q.graphqlRemaining : null)
                        .graphqlLimit(q.graphqlLimit >= 0 ? q.graphqlLimit : null)
                        .graphqlCalls(q.graphqlCalls)
                        .graphqlPointsUsed(q.graphqlPointsUsed)
                        .graphql429Count(q.graphql429Count)
                        .updatedAt(q.updatedAt)
                        .series(series)
                        .githubRestConsumed(hint.githubRestConsumed())
                        .appRestConsumed(hint.appRestConsumed())
                        .externalRestSuspect(hint.externalRestSuspect())
                        .build());
            }
        }
        installViews.sort(Comparator
                .comparing((InstallQuotaView v) -> v.getRestRemaining() == null ? Integer.MAX_VALUE : v.getRestRemaining())
                .thenComparing(InstallQuotaView::getProvider, Comparator.nullsLast(String::compareToIgnoreCase)));

        List<RepoHotspotView> repoViews = new ArrayList<>();
        for (RepoHotspot h : repos.values()) {
            synchronized (h) {
                long score = h.gitFetches + h.gitPushes + h.graphqlCalls
                        + (h.gitThrottles * 50L) + (h.graphql429Count * 50L);
                repoViews.add(RepoHotspotView.builder()
                        .provider(h.provider)
                        .repoFullName(h.repoFullName)
                        .gitFetches(h.gitFetches)
                        .gitPushes(h.gitPushes)
                        .gitThrottles(h.gitThrottles)
                        .graphqlCalls(h.graphqlCalls)
                        .graphqlPointsUsed(h.graphqlPointsUsed)
                        .graphql429Count(h.graphql429Count)
                        .heatScore(score)
                        .updatedAt(h.updatedAt)
                        .build());
            }
        }
        repoViews.sort(Comparator.comparingLong(RepoHotspotView::getHeatScore).reversed());
        if (repoViews.size() > Math.max(1, topRepos)) {
            repoViews = new ArrayList<>(repoViews.subList(0, topRepos));
        }

        return Snapshot.builder()
                .capturedAt(Instant.now())
                .installations(installViews)
                .hottestRepos(repoViews)
                .build();
    }

    public static String installKey(String provider, String installationKey) {
        String p = provider == null ? "unknown" : provider.trim().toLowerCase();
        String i = installationKey == null || installationKey.isBlank() ? "default" : installationKey.trim();
        return p + "|" + i;
    }

    public static String repoKey(String provider, String repoFullName) {
        return (provider == null ? "unknown" : provider.trim().toLowerCase())
                + "|" + repoFullName.trim().toLowerCase();
    }

    /**
     * Compare GitHub remaining drops to this JVM's observed REST call growth.
     * Positive {@code externalRestSuspect} means remaining fell faster than our call count —
     * another client (or another pod) may be using the same installation/token.
     */
    static ExternalUsageHint computeExternalHint(List<QuotaSampleView> series) {
        if (series == null || series.size() < 2) {
            return new ExternalUsageHint(0, 0, 0);
        }
        long github = 0;
        long app = 0;
        for (int i = 1; i < series.size(); i++) {
            QuotaSampleView prev = series.get(i - 1);
            QuotaSampleView curr = series.get(i);
            if (prev.getRestRemaining() == null || curr.getRestRemaining() == null) {
                continue;
            }
            int remDelta = prev.getRestRemaining() - curr.getRestRemaining();
            if (remDelta <= 0) {
                // Window reset or no change — skip for delta accounting
                continue;
            }
            long appDelta = Math.max(0, curr.getAppRestCalls() - prev.getAppRestCalls());
            github += remDelta;
            app += appDelta;
        }
        long external = Math.max(0, github - app);
        return new ExternalUsageHint(github, app, external);
    }

    private static final int MAX_SAMPLES = 120;
    private static final long SAMPLE_MIN_INTERVAL_MS = 2_000L;

    private static final class InstallQuota {
        final String provider;
        final String installationKey;
        int restRemaining = -1;
        int restLimit = -1;
        Instant restResetAt;
        long restCalls;
        long rest429Count;
        int graphqlRemaining = -1;
        int graphqlLimit = -1;
        long graphqlCalls;
        long graphqlPointsUsed;
        long graphql429Count;
        Instant updatedAt = Instant.now();
        final ArrayDeque<QuotaSample> series = new ArrayDeque<>();

        private InstallQuota(String provider, String installationKey) {
            this.provider = provider;
            this.installationKey = installationKey == null || installationKey.isBlank() ? "default" : installationKey;
        }

        static InstallQuota create(String provider, String installationKey) {
            return new InstallQuota(provider, installationKey);
        }

        void maybeSample() {
            Instant now = Instant.now();
            Integer rem = restRemaining >= 0 ? restRemaining : null;
            Integer gRem = graphqlRemaining >= 0 ? graphqlRemaining : null;
            QuotaSample last = series.peekLast();
            boolean remainingChanged = last == null
                    || !java.util.Objects.equals(last.restRemaining, rem)
                    || !java.util.Objects.equals(last.graphqlRemaining, gRem);
            long ageMs = last == null
                    ? Long.MAX_VALUE
                    : java.time.Duration.between(last.at, now).toMillis();
            // Sample on remaining change, or a throttled heartbeat so the sparkline keeps moving.
            if (last != null && !remainingChanged && ageMs < SAMPLE_MIN_INTERVAL_MS) {
                return;
            }
            series.addLast(new QuotaSample(
                    now,
                    rem,
                    restLimit >= 0 ? restLimit : null,
                    restCalls,
                    gRem,
                    graphqlCalls
            ));
            while (series.size() > MAX_SAMPLES) {
                series.removeFirst();
            }
        }

        List<QuotaSampleView> copySeries() {
            List<QuotaSampleView> out = new ArrayList<>(series.size());
            for (QuotaSample s : series) {
                out.add(QuotaSampleView.builder()
                        .at(s.at)
                        .restRemaining(s.restRemaining)
                        .restLimit(s.restLimit)
                        .appRestCalls(s.appRestCalls)
                        .graphqlRemaining(s.graphqlRemaining)
                        .appGraphqlCalls(s.appGraphqlCalls)
                        .build());
            }
            return out;
        }
    }

    private record QuotaSample(
            Instant at,
            Integer restRemaining,
            Integer restLimit,
            long appRestCalls,
            Integer graphqlRemaining,
            long appGraphqlCalls
    ) {
    }

    record ExternalUsageHint(long githubRestConsumed, long appRestConsumed, long externalRestSuspect) {
    }

    private static final class RepoHotspot {
        final String provider;
        final String repoFullName;
        long gitFetches;
        long gitPushes;
        long gitThrottles;
        long graphqlCalls;
        long graphqlPointsUsed;
        long graphql429Count;
        Instant updatedAt = Instant.now();

        private RepoHotspot(String provider, String repoFullName) {
            this.provider = provider;
            this.repoFullName = repoFullName;
        }

        static RepoHotspot create(String provider, String repoFullName) {
            return new RepoHotspot(provider, repoFullName);
        }
    }

    @Data
    @Builder
    public static class Snapshot {
        private Instant capturedAt;
        private List<InstallQuotaView> installations;
        private List<RepoHotspotView> hottestRepos;
    }

    @Data
    @Builder
    public static class InstallQuotaView {
        private String provider;
        private String installationKey;
        private Integer restRemaining;
        private Integer restLimit;
        private Instant restResetAt;
        private long restCalls;
        private long rest429Count;
        private Integer graphqlRemaining;
        private Integer graphqlLimit;
        private long graphqlCalls;
        private long graphqlPointsUsed;
        private long graphql429Count;
        private Instant updatedAt;
        @Builder.Default
        private List<QuotaSampleView> series = new ArrayList<>();
        private long githubRestConsumed;
        private long appRestConsumed;
        private long externalRestSuspect;
    }

    @Data
    @Builder
    public static class QuotaSampleView {
        private Instant at;
        private Integer restRemaining;
        private Integer restLimit;
        private long appRestCalls;
        private Integer graphqlRemaining;
        private long appGraphqlCalls;
    }

    @Data
    @Builder
    public static class RepoHotspotView {
        private String provider;
        private String repoFullName;
        private long gitFetches;
        private long gitPushes;
        private long gitThrottles;
        private long graphqlCalls;
        private long graphqlPointsUsed;
        private long graphql429Count;
        private long heatScore;
        private Instant updatedAt;
    }
}
