package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemEngineConfigResponse {
    private Long id;
    private String localDir;
    private String nasDir;
    private long maxDiskQuotaMb;
    private int maxCachedRepos;
    private int retentionHours;

    private int maxConcurrentPushes;
    private int metadataSyncIntervalSeconds;

    private int maxRetryAttempts;
    private long retryInitialIntervalMs;
    private double retryMultiplier;
    private long retryMaxIntervalMs;

    private int circuitBreakerFailureThreshold;
    private int circuitBreakerResetTimeoutSeconds;

    private boolean suppressMirrorActionsTriggers;

    // Enterprise Logging Status & Config
    private String loggingSink;
    private String loggingLevel;
    private String splunkHecUrl;
    private String splunkHecTokenMasked;
    private boolean hasSplunkHecToken;
    private String splunkIndex;
    private String splunkSourceType;
    private String logstashHost;
    private String syslogHost;
    private String rollingFilePath;
    private boolean jsonStructuredEnabled;

    // Runtime Circuit Breaker Status
    private String circuitBreakerState; // CLOSED, OPEN, HALF_OPEN
    private int currentConsecutiveFailures;
    private Instant lastStateTransitionAt;
    private String lastProbeMessage;
    private boolean lastProbeSuccess;

    private Instant updatedAt;
}
