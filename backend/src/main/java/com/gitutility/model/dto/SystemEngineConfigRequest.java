package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemEngineConfigRequest {
    private String localDir;
    private String nasDir;
    private Long maxDiskQuotaMb;
    private Integer maxCachedRepos;
    private Integer retentionHours;

    private Integer maxConcurrentPushes;
    private Integer metadataSyncIntervalSeconds;

    private Integer maxRetryAttempts;
    private Long retryInitialIntervalMs;
    private Double retryMultiplier;
    private Long retryMaxIntervalMs;

    private Integer circuitBreakerFailureThreshold;
    private Integer circuitBreakerResetTimeoutSeconds;

    private Boolean suppressMirrorActionsTriggers;

    // Enterprise Logging
    private String loggingSink;
    private String loggingLevel;
    private String splunkHecUrl;
    private String splunkHecToken;
    private String splunkIndex;
    private String splunkSourceType;
    private String logstashHost;
    private String syslogHost;
    private String rollingFilePath;
    private Boolean jsonStructuredEnabled;
}
