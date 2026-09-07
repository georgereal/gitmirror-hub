package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterRuntimeMetricsResponse {

    private Instant capturedAt;
    private int instanceCount;
    private int liveInstanceCount;
    @Builder.Default
    private List<InstanceMetrics> instances = new ArrayList<>();
    private ClusterTotals totals;
    /** Fleet roll-up of external REST usage by install / credential key. */
    @Builder.Default
    private List<InstallApiUsageAggregate> apiUsageByInstall = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstanceMetrics {
        private String instanceId;
        private boolean stale;
        private Instant updatedAt;
        private RuntimeMetricsResponse metrics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClusterTotals {
        private double executorActive;
        private double executorQueued;
        private int laneUnacked;
        private double actionsCancelsTotal;
        private int pausedInstances;
        private int openCircuitInstances;
        private int threadsLive;
        private int threadsDaemon;
        private int threadsPeak;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstallApiUsageAggregate {
        private String installKey;
        private String provider;
        private String label;
        private long restCallCount;
        private double restCallsPerMinute;
        private Integer rateLimitRemaining;
        private Integer rateLimitLimit;
        private int rateLimit429Count;
        private Instant lastSeenAt;
        @Builder.Default
        private List<String> reportingInstances = new ArrayList<>();
    }
}
