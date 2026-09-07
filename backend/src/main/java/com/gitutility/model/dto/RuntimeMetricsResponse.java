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
public class RuntimeMetricsResponse {

    private String instanceId;
    private Instant capturedAt;
    private JvmMemory jvm;
    private JvmThreads threads;
    private CircuitBreakerSnapshot circuitBreaker;
    private boolean consumerPaused;
    @Builder.Default
    private List<ExecutorPoolSnapshot> executors = new ArrayList<>();
    @Builder.Default
    private List<LaneSnapshot> lanes = new ArrayList<>();
    @Builder.Default
    private List<JobOutcomeSnapshot> jobOutcomes = new ArrayList<>();
    @Builder.Default
    private List<InstallApiUsageSnapshot> apiUsageByInstall = new ArrayList<>();
    private double actionsCancelsTotal;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JvmMemory {
        private long heapUsedBytes;
        private long heapMaxBytes;
        private double heapUsedPercent;
        private long nonHeapUsedBytes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JvmThreads {
        private int live;
        private int daemon;
        private int peak;
        private long started;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstallApiUsageSnapshot {
        /** Stable key e.g. {@code github:12345}, {@code ghes:99}, {@code github:pat}. */
        private String installKey;
        private String provider;
        private String label;
        private long restCallCount;
        private double restCallsPerMinute;
        private Integer rateLimitRemaining;
        private Integer rateLimitLimit;
        private int rateLimit429Count;
        private Instant lastSeenAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CircuitBreakerSnapshot {
        private String state;
        private int consecutiveFailures;
        private String lastProbeMessage;
        private boolean lastProbeSuccess;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutorPoolSnapshot {
        private String name;
        private double active;
        private double queued;
        private double poolSize;
        private double completed;
        private Double maxPoolSize;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaneSnapshot {
        private String lane;
        private int unacked;
        private int configuredConsumers;
        private int activeConsumers;
        private boolean running;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JobOutcomeSnapshot {
        private String lane;
        private String status;
        private double count;
        private Double meanDurationMs;
    }
}
