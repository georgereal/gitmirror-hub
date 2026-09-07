package com.gitutility.service;

import com.gitutility.model.dto.RuntimeMetricsResponse;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.repository.GitHubAppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-level external REST usage keyed by SCM install / credential identity.
 * Low cardinality (configured installs only). Aggregated across pods via heartbeats.
 */
@Component
@RequiredArgsConstructor
public class InstallApiUsageTracker {

    private static final long ROLLING_WINDOW_MS = 60_000L;

    private final GitHubAppConfigRepository configRepository;
    private final Map<String, InstallMeter> meters = new ConcurrentHashMap<>();

    public void recordRestCall(String installKey, String provider, String label,
                               Integer rateLimitRemaining, Integer rateLimitLimit, boolean rateLimited429) {
        if (installKey == null || installKey.isBlank()) {
            return;
        }
        InstallMeter meter = meters.computeIfAbsent(installKey, k -> new InstallMeter(k, provider, label));
        if (provider != null && !provider.isBlank()) {
            meter.provider.set(provider);
        }
        if (label != null && !label.isBlank()) {
            meter.label.set(label);
        }
        meter.restCallCount.incrementAndGet();
        meter.recordRestTimestamp(System.currentTimeMillis());
        if (rateLimitRemaining != null) {
            meter.rateLimitRemaining.set(rateLimitRemaining);
        }
        if (rateLimitLimit != null) {
            meter.rateLimitLimit.set(rateLimitLimit);
        }
        if (rateLimited429) {
            meter.rateLimit429Count.incrementAndGet();
        }
        meter.lastSeenAt.set(Instant.now());
    }

    public List<RuntimeMetricsResponse.InstallApiUsageSnapshot> snapshot() {
        ensureConfiguredInstalls();
        List<RuntimeMetricsResponse.InstallApiUsageSnapshot> list = new ArrayList<>();
        long nowMs = System.currentTimeMillis();
        for (InstallMeter meter : meters.values()) {
            list.add(RuntimeMetricsResponse.InstallApiUsageSnapshot.builder()
                    .installKey(meter.installKey)
                    .provider(meter.provider.get())
                    .label(meter.label.get())
                    .restCallCount(meter.restCallCount.get())
                    .restCallsPerMinute(meter.restPerMinute(nowMs))
                    .rateLimitRemaining(meter.rateLimitRemaining.get())
                    .rateLimitLimit(meter.rateLimitLimit.get())
                    .rateLimit429Count(meter.rateLimit429Count.get())
                    .lastSeenAt(meter.lastSeenAt.get())
                    .build());
        }
        list.sort(Comparator.comparing(RuntimeMetricsResponse.InstallApiUsageSnapshot::getInstallKey,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    private void ensureConfiguredInstalls() {
        GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
        if (config == null) {
            return;
        }
        String ghInstall = blankToNull(config.getInstallationId());
        if (ghInstall != null) {
            meters.computeIfAbsent("github:" + ghInstall,
                    k -> new InstallMeter(k, "github", "GitHub App install " + ghInstall));
        } else if ("GITHUB_APP".equalsIgnoreCase(config.getAuthType())) {
            meters.computeIfAbsent("github:app-unbound",
                    k -> new InstallMeter(k, "github", "GitHub App (no install id)"));
        } else if (blankToNull(config.getDefaultPatToken()) != null
                || "PERSONAL_ACCESS_TOKEN".equalsIgnoreCase(config.getAuthType())) {
            meters.computeIfAbsent("github:pat",
                    k -> new InstallMeter(k, "github", "GitHub PAT"));
        }

        String ghesInstall = blankToNull(config.getGhesInstallationId());
        if (ghesInstall != null) {
            String host = blankToNull(config.getGhesHostUrl());
            String label = host != null
                    ? "GHES App install " + ghesInstall + " @ " + host
                    : "GHES App install " + ghesInstall;
            meters.computeIfAbsent("ghes:" + ghesInstall,
                    k -> new InstallMeter(k, "ghes", label));
        } else if ("GITHUB_APP".equalsIgnoreCase(config.getGhesAuthType())
                && blankToNull(config.getGhesHostUrl()) != null) {
            meters.computeIfAbsent("ghes:app-unbound",
                    k -> new InstallMeter(k, "ghes", "GHES App (no install id)"));
        } else if (blankToNull(config.getGhesPatToken()) != null) {
            meters.computeIfAbsent("ghes:pat",
                    k -> new InstallMeter(k, "ghes", "GHES PAT"));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class InstallMeter {
        final String installKey;
        final AtomicReference<String> provider;
        final AtomicReference<String> label;
        final AtomicLong restCallCount = new AtomicLong();
        final AtomicInteger rateLimit429Count = new AtomicInteger();
        final AtomicReference<Integer> rateLimitRemaining = new AtomicReference<>();
        final AtomicReference<Integer> rateLimitLimit = new AtomicReference<>();
        final AtomicReference<Instant> lastSeenAt = new AtomicReference<>();
        final Deque<Long> restTimestampsMs = new ArrayDeque<>();

        InstallMeter(String installKey, String provider, String label) {
            this.installKey = installKey;
            this.provider = new AtomicReference<>(provider);
            this.label = new AtomicReference<>(label);
        }

        void recordRestTimestamp(long nowMs) {
            synchronized (restTimestampsMs) {
                restTimestampsMs.addLast(nowMs);
                while (!restTimestampsMs.isEmpty() && nowMs - restTimestampsMs.peekFirst() > ROLLING_WINDOW_MS) {
                    restTimestampsMs.removeFirst();
                }
            }
        }

        double restPerMinute(long nowMs) {
            synchronized (restTimestampsMs) {
                while (!restTimestampsMs.isEmpty() && nowMs - restTimestampsMs.peekFirst() > ROLLING_WINDOW_MS) {
                    restTimestampsMs.removeFirst();
                }
                return restTimestampsMs.size();
            }
        }
    }
}
