package com.gitutility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitutility.model.dto.ClusterRuntimeMetricsResponse;
import com.gitutility.model.dto.RuntimeMetricsResponse;
import com.gitutility.model.entity.InstanceHeartbeat;
import com.gitutility.repository.InstanceHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstanceHeartbeatService {

    private final InstanceHeartbeatRepository heartbeatRepository;
    private final RuntimeMetricsService runtimeMetricsService;
    private final InstanceIdentity instanceIdentity;
    private final ObjectMapper objectMapper;

    @Value("${git-utility.cluster.heartbeat-stale-seconds:15}")
    private int staleSeconds;

    @Scheduled(fixedDelayString = "${git-utility.cluster.heartbeat-interval-ms:3000}")
    @Transactional
    public void publishHeartbeat() {
        try {
            RuntimeMetricsResponse snapshot = runtimeMetricsService.snapshot();
            String json = objectMapper.writeValueAsString(snapshot);
            Instant now = Instant.now();
            heartbeatRepository.save(InstanceHeartbeat.builder()
                    .instanceId(instanceIdentity.getInstanceId())
                    .updatedAt(now)
                    .payloadJson(json)
                    .build());
            Instant cutoff = now.minus(Math.max(60, staleSeconds * 10L), ChronoUnit.SECONDS);
            heartbeatRepository.deleteOlderThan(cutoff);
        } catch (Exception e) {
            log.debug("Instance heartbeat publish notice: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public ClusterRuntimeMetricsResponse clusterSnapshot() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Math.max(5, staleSeconds), ChronoUnit.SECONDS);
        List<ClusterRuntimeMetricsResponse.InstanceMetrics> instances = new ArrayList<>();

        double executorActive = 0;
        double executorQueued = 0;
        int laneUnacked = 0;
        double actionsCancels = 0;
        int paused = 0;
        int openCb = 0;
        int live = 0;
        int threadsLive = 0;
        int threadsDaemon = 0;
        int threadsPeak = 0;

        Map<String, ClusterRuntimeMetricsResponse.InstallApiUsageAggregate> byInstall = new LinkedHashMap<>();

        for (InstanceHeartbeat row : heartbeatRepository.findAll()) {
            boolean stale = row.getUpdatedAt() == null || row.getUpdatedAt().isBefore(staleBefore);
            RuntimeMetricsResponse metrics = null;
            try {
                metrics = objectMapper.readValue(row.getPayloadJson(), RuntimeMetricsResponse.class);
            } catch (Exception e) {
                log.debug("Failed to parse heartbeat for {}: {}", row.getInstanceId(), e.getMessage());
            }
            instances.add(ClusterRuntimeMetricsResponse.InstanceMetrics.builder()
                    .instanceId(row.getInstanceId())
                    .stale(stale)
                    .updatedAt(row.getUpdatedAt())
                    .metrics(metrics)
                    .build());
            if (!stale && metrics != null) {
                live++;
                if (metrics.getExecutors() != null) {
                    for (var ex : metrics.getExecutors()) {
                        executorActive += ex.getActive();
                        executorQueued += ex.getQueued();
                    }
                }
                if (metrics.getLanes() != null) {
                    for (var lane : metrics.getLanes()) {
                        laneUnacked += lane.getUnacked();
                    }
                }
                actionsCancels += metrics.getActionsCancelsTotal();
                if (metrics.isConsumerPaused()) {
                    paused++;
                }
                if (metrics.getCircuitBreaker() != null
                        && "OPEN".equalsIgnoreCase(metrics.getCircuitBreaker().getState())) {
                    openCb++;
                }
                if (metrics.getThreads() != null) {
                    threadsLive += metrics.getThreads().getLive();
                    threadsDaemon += metrics.getThreads().getDaemon();
                    threadsPeak += metrics.getThreads().getPeak();
                }
                mergeInstallUsage(byInstall, row.getInstanceId(), metrics.getApiUsageByInstall());
            }
        }

        instances.sort(Comparator.comparing(ClusterRuntimeMetricsResponse.InstanceMetrics::getInstanceId,
                Comparator.nullsLast(String::compareToIgnoreCase)));

        List<ClusterRuntimeMetricsResponse.InstallApiUsageAggregate> installUsage =
                new ArrayList<>(byInstall.values());
        installUsage.sort(Comparator.comparing(ClusterRuntimeMetricsResponse.InstallApiUsageAggregate::getInstallKey,
                Comparator.nullsLast(String::compareToIgnoreCase)));

        return ClusterRuntimeMetricsResponse.builder()
                .capturedAt(now)
                .instanceCount(instances.size())
                .liveInstanceCount(live)
                .instances(instances)
                .apiUsageByInstall(installUsage)
                .totals(ClusterRuntimeMetricsResponse.ClusterTotals.builder()
                        .executorActive(executorActive)
                        .executorQueued(executorQueued)
                        .laneUnacked(laneUnacked)
                        .actionsCancelsTotal(actionsCancels)
                        .pausedInstances(paused)
                        .openCircuitInstances(openCb)
                        .threadsLive(threadsLive)
                        .threadsDaemon(threadsDaemon)
                        .threadsPeak(threadsPeak)
                        .build())
                .build();
    }

    private static void mergeInstallUsage(
            Map<String, ClusterRuntimeMetricsResponse.InstallApiUsageAggregate> byInstall,
            String instanceId,
            List<RuntimeMetricsResponse.InstallApiUsageSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        for (RuntimeMetricsResponse.InstallApiUsageSnapshot snap : snapshots) {
            if (snap.getInstallKey() == null || snap.getInstallKey().isBlank()) {
                continue;
            }
            ClusterRuntimeMetricsResponse.InstallApiUsageAggregate agg = byInstall.computeIfAbsent(
                    snap.getInstallKey(),
                    key -> ClusterRuntimeMetricsResponse.InstallApiUsageAggregate.builder()
                            .installKey(key)
                            .provider(snap.getProvider())
                            .label(snap.getLabel())
                            .reportingInstances(new ArrayList<>())
                            .build());
            if (snap.getProvider() != null && !snap.getProvider().isBlank()) {
                agg.setProvider(snap.getProvider());
            }
            if (snap.getLabel() != null && !snap.getLabel().isBlank()) {
                agg.setLabel(snap.getLabel());
            }
            agg.setRestCallCount(agg.getRestCallCount() + snap.getRestCallCount());
            agg.setRestCallsPerMinute(round1(agg.getRestCallsPerMinute() + snap.getRestCallsPerMinute()));
            agg.setRateLimit429Count(agg.getRateLimit429Count() + snap.getRateLimit429Count());
            if (!agg.getReportingInstances().contains(instanceId)) {
                agg.getReportingInstances().add(instanceId);
            }
            // Shared install quota: prefer the freshest remaining/limit observation.
            boolean fresher = snap.getLastSeenAt() != null
                    && (agg.getLastSeenAt() == null || snap.getLastSeenAt().isAfter(agg.getLastSeenAt()));
            if (fresher || agg.getRateLimitRemaining() == null) {
                if (snap.getRateLimitRemaining() != null) {
                    agg.setRateLimitRemaining(snap.getRateLimitRemaining());
                }
                if (snap.getRateLimitLimit() != null) {
                    agg.setRateLimitLimit(snap.getRateLimitLimit());
                }
            }
            if (fresher) {
                agg.setLastSeenAt(snap.getLastSeenAt());
            } else if (agg.getLastSeenAt() == null) {
                agg.setLastSeenAt(snap.getLastSeenAt());
            }
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
