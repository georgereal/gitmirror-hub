package com.gitutility.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "system_engine_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemEngineConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Storage Configuration ---
    @Column(nullable = false)
    @Builder.Default
    private String localDir = "/tmp/git-utility-mirrors";

    @Column(nullable = false)
    @Builder.Default
    private String nasDir = "/tmp/git-utility-nas-mirrors";

    @Column(nullable = false)
    @Builder.Default
    private long maxDiskQuotaMb = 51200L; // 50 GB

    @Column(nullable = false)
    @Builder.Default
    private int maxCachedRepos = 1000;

    @Column(nullable = false)
    @Builder.Default
    private int retentionHours = 72;

    // --- Concurrency & Rate Limiting ---
    @Column(nullable = false)
    @Builder.Default
    private int maxConcurrentPushes = 5;

    @Column(nullable = false)
    @Builder.Default
    private int metadataSyncIntervalSeconds = 30;

    // --- Jittered Exponential Backoff Retry Strategy ---
    @Column(nullable = false)
    @Builder.Default
    private int maxRetryAttempts = 3;

    @Column(nullable = false)
    @Builder.Default
    private long retryInitialIntervalMs = 3000L;

    @Column(nullable = false)
    @Builder.Default
    private double retryMultiplier = 2.0;

    @Column(nullable = false)
    @Builder.Default
    private long retryMaxIntervalMs = 30000L;

    // --- Circuit Breaker ---
    @Column(nullable = false)
    @Builder.Default
    private int circuitBreakerFailureThreshold = 5;

    @Column(nullable = false)
    @Builder.Default
    private int circuitBreakerResetTimeoutSeconds = 30;

    /**
     * When true, Hub-generated GitHub/GHES writes must use App identity and any Actions runs
     * attributed to the mirror App bot are cancelled after sync writes.
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    @Builder.Default
    private boolean suppressMirrorActionsTriggers = true;

    // --- Enterprise Multi-Sink Logging Configuration ---
    @Column(nullable = false)
    @Builder.Default
    private String loggingSink = "CONSOLE"; // CONSOLE, SPLUNK_HEC, LOGSTASH_ELK, SYSLOG, ROLLING_FILE, DUAL_CONSOLE_SPLUNK

    @Column(nullable = false)
    @Builder.Default
    private String loggingLevel = "INFO"; // DEBUG, INFO, WARN, ERROR

    @Column
    private String splunkHecUrl;

    @Column
    private String splunkHecToken;

    @Column
    @Builder.Default
    private String splunkIndex = "main";

    @Column
    @Builder.Default
    private String splunkSourceType = "_json";

    @Column
    private String logstashHost;

    @Column
    private String syslogHost;

    @Column
    @Builder.Default
    private String rollingFilePath = "/tmp/git-utility-mirrors/logs/git-utility.log";

    @Column(nullable = false)
    @Builder.Default
    private boolean jsonStructuredEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
