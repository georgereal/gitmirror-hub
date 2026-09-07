package com.gitutility.service;

import com.gitutility.model.dto.SystemEngineConfigRequest;
import com.gitutility.model.dto.SystemEngineConfigResponse;
import com.gitutility.model.entity.SystemEngineConfig;
import com.gitutility.repository.SystemEngineConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
public class SystemEngineConfigService {

    private final SystemEngineConfigRepository configRepository;
    private final CircuitBreakerManagerService circuitBreakerManager;
    private final StorageTieringService storageTieringService;
    private final QueueConsumerService queueConsumerService;
    private final EnterpriseLoggingService enterpriseLoggingService;

    @Value("${git-utility.storage.local-dir:/tmp/git-utility-mirrors}")
    private String defaultLocalDir;

    @Value("${git-utility.storage.nas-dir:/tmp/git-utility-nas-mirrors}")
    private String defaultNasDir;

    @Value("${git-utility.storage.max-disk-quota-mb:51200}")
    private long defaultMaxDiskQuotaMb;

    @Value("${git-utility.storage.max-cached-repos:1000}")
    private int defaultMaxCachedRepos;

    @Value("${git-utility.storage.retention-hours:72}")
    private int defaultRetentionHours;

    @Value("${git-utility.throttle.max-concurrent-git-pushes:5}")
    private int defaultMaxConcurrentPushes;

    @Value("${git-utility.throttle.metadata-sync-interval-seconds:30}")
    private int defaultMetadataSyncIntervalSeconds;

    @Value("${git-utility.circuit-breaker.failure-threshold:5}")
    private int defaultCircuitBreakerFailureThreshold;

    @Value("${git-utility.circuit-breaker.reset-timeout-seconds:30}")
    private int defaultCircuitBreakerResetTimeoutSeconds;

    public SystemEngineConfigService(
            SystemEngineConfigRepository configRepository,
            CircuitBreakerManagerService circuitBreakerManager,
            @Lazy StorageTieringService storageTieringService,
            @Lazy QueueConsumerService queueConsumerService,
            @Lazy EnterpriseLoggingService enterpriseLoggingService) {
        this.configRepository = configRepository;
        this.circuitBreakerManager = circuitBreakerManager;
        this.storageTieringService = storageTieringService;
        this.queueConsumerService = queueConsumerService;
        this.enterpriseLoggingService = enterpriseLoggingService;
    }

    @PostConstruct
    public void init() {
        getOrCreateConfig();
    }

    @Transactional
    public SystemEngineConfig getOrCreateConfig() {
        return configRepository.findTopByOrderByIdAsc().orElseGet(() -> {
            log.info("Seeding initial SystemEngineConfig in database");
            SystemEngineConfig config = SystemEngineConfig.builder()
                    .localDir(defaultLocalDir)
                    .nasDir(defaultNasDir)
                    .maxDiskQuotaMb(defaultMaxDiskQuotaMb)
                    .maxCachedRepos(defaultMaxCachedRepos)
                    .retentionHours(defaultRetentionHours)
                    .maxConcurrentPushes(defaultMaxConcurrentPushes)
                    .metadataSyncIntervalSeconds(defaultMetadataSyncIntervalSeconds)
                    .maxRetryAttempts(3)
                    .retryInitialIntervalMs(3000L)
                    .retryMultiplier(2.0)
                    .retryMaxIntervalMs(30000L)
                    .circuitBreakerFailureThreshold(defaultCircuitBreakerFailureThreshold)
                    .circuitBreakerResetTimeoutSeconds(defaultCircuitBreakerResetTimeoutSeconds)
                    .updatedAt(Instant.now())
                    .build();
            return configRepository.save(config);
        });
    }

    public SystemEngineConfigResponse getConfigResponse() {
        SystemEngineConfig config = getOrCreateConfig();
        return toResponse(config);
    }

    @Transactional
    public SystemEngineConfigResponse updateConfig(SystemEngineConfigRequest req) {
        SystemEngineConfig config = getOrCreateConfig();

        if (req.getLocalDir() != null && !req.getLocalDir().isBlank()) {
            config.setLocalDir(req.getLocalDir().trim());
        }
        if (req.getNasDir() != null && !req.getNasDir().isBlank()) {
            config.setNasDir(req.getNasDir().trim());
        }
        if (req.getMaxDiskQuotaMb() != null && req.getMaxDiskQuotaMb() > 0) {
            config.setMaxDiskQuotaMb(req.getMaxDiskQuotaMb());
        }
        if (req.getMaxCachedRepos() != null && req.getMaxCachedRepos() > 0) {
            config.setMaxCachedRepos(req.getMaxCachedRepos());
        }
        if (req.getRetentionHours() != null && req.getRetentionHours() > 0) {
            config.setRetentionHours(req.getRetentionHours());
        }

        if (req.getMaxConcurrentPushes() != null && req.getMaxConcurrentPushes() > 0) {
            config.setMaxConcurrentPushes(Math.min(50, req.getMaxConcurrentPushes()));
        }
        if (req.getMetadataSyncIntervalSeconds() != null && req.getMetadataSyncIntervalSeconds() >= 0) {
            config.setMetadataSyncIntervalSeconds(req.getMetadataSyncIntervalSeconds());
        }

        if (req.getMaxRetryAttempts() != null && req.getMaxRetryAttempts() >= 1) {
            config.setMaxRetryAttempts(Math.min(10, req.getMaxRetryAttempts()));
        }
        if (req.getRetryInitialIntervalMs() != null && req.getRetryInitialIntervalMs() > 0) {
            config.setRetryInitialIntervalMs(req.getRetryInitialIntervalMs());
        }
        if (req.getRetryMultiplier() != null && req.getRetryMultiplier() >= 1.0) {
            config.setRetryMultiplier(req.getRetryMultiplier());
        }
        if (req.getRetryMaxIntervalMs() != null && req.getRetryMaxIntervalMs() > 0) {
            config.setRetryMaxIntervalMs(req.getRetryMaxIntervalMs());
        }

        if (req.getCircuitBreakerFailureThreshold() != null && req.getCircuitBreakerFailureThreshold() > 0) {
            config.setCircuitBreakerFailureThreshold(req.getCircuitBreakerFailureThreshold());
        }
        if (req.getCircuitBreakerResetTimeoutSeconds() != null && req.getCircuitBreakerResetTimeoutSeconds() > 0) {
            config.setCircuitBreakerResetTimeoutSeconds(req.getCircuitBreakerResetTimeoutSeconds());
        }
        if (req.getSuppressMirrorActionsTriggers() != null) {
            config.setSuppressMirrorActionsTriggers(req.getSuppressMirrorActionsTriggers());
        }

        // Logging parameters
        if (req.getLoggingSink() != null && !req.getLoggingSink().isBlank()) {
            config.setLoggingSink(req.getLoggingSink().trim().toUpperCase());
        }
        if (req.getLoggingLevel() != null && !req.getLoggingLevel().isBlank()) {
            config.setLoggingLevel(req.getLoggingLevel().trim().toUpperCase());
        }
        if (req.getSplunkHecUrl() != null) {
            config.setSplunkHecUrl(req.getSplunkHecUrl().trim());
        }
        if (req.getSplunkHecToken() != null && !req.getSplunkHecToken().isBlank()) {
            config.setSplunkHecToken(req.getSplunkHecToken().trim());
        }
        if (req.getSplunkIndex() != null && !req.getSplunkIndex().isBlank()) {
            config.setSplunkIndex(req.getSplunkIndex().trim());
        }
        if (req.getSplunkSourceType() != null && !req.getSplunkSourceType().isBlank()) {
            config.setSplunkSourceType(req.getSplunkSourceType().trim());
        }
        if (req.getLogstashHost() != null) {
            config.setLogstashHost(req.getLogstashHost().trim());
        }
        if (req.getSyslogHost() != null) {
            config.setSyslogHost(req.getSyslogHost().trim());
        }
        if (req.getRollingFilePath() != null && !req.getRollingFilePath().isBlank()) {
            config.setRollingFilePath(req.getRollingFilePath().trim());
        }
        if (req.getJsonStructuredEnabled() != null) {
            config.setJsonStructuredEnabled(req.getJsonStructuredEnabled());
        }

        config.setUpdatedAt(Instant.now());
        SystemEngineConfig saved = configRepository.save(config);

        // Apply hot-reload updates across subsystems
        applyHotReload(saved);

        return toResponse(saved);
    }

    public void applyHotReload(SystemEngineConfig config) {
        log.info("Applying dynamic runtime hot-reload for SystemEngineConfig...");

        // 1. Update Storage Tiering Service
        if (storageTieringService != null) {
            storageTieringService.updateStorageParameters(
                    config.getLocalDir(),
                    config.getNasDir(),
                    config.getMaxDiskQuotaMb(),
                    config.getMaxCachedRepos(),
                    config.getRetentionHours()
            );
        }

        // 2. Update Queue Consumer Concurrency & Throttling
        if (queueConsumerService != null) {
            queueConsumerService.updateOperationalParameters(
                    config.getMaxConcurrentPushes(),
                    config.getMetadataSyncIntervalSeconds()
            );
        }

        // 3. Update Circuit Breaker
        if (circuitBreakerManager != null) {
            circuitBreakerManager.updateThresholds(
                    config.getCircuitBreakerFailureThreshold(),
                    config.getCircuitBreakerResetTimeoutSeconds()
            );
        }

        // 4. Update Enterprise Logging Service
        if (enterpriseLoggingService != null) {
            enterpriseLoggingService.updateLoggingConfig(config);
        }
    }

    private SystemEngineConfigResponse toResponse(SystemEngineConfig config) {
        String token = config.getSplunkHecToken();
        String tokenMasked = null;
        boolean hasToken = token != null && !token.isBlank();
        if (hasToken) {
            tokenMasked = token.length() <= 8 ? "********" : token.substring(0, 4) + "..." + token.substring(token.length() - 4);
        }

        return SystemEngineConfigResponse.builder()
                .id(config.getId())
                .localDir(config.getLocalDir())
                .nasDir(config.getNasDir())
                .maxDiskQuotaMb(config.getMaxDiskQuotaMb())
                .maxCachedRepos(config.getMaxCachedRepos())
                .retentionHours(config.getRetentionHours())
                .maxConcurrentPushes(config.getMaxConcurrentPushes())
                .metadataSyncIntervalSeconds(config.getMetadataSyncIntervalSeconds())
                .maxRetryAttempts(config.getMaxRetryAttempts())
                .retryInitialIntervalMs(config.getRetryInitialIntervalMs())
                .retryMultiplier(config.getRetryMultiplier())
                .retryMaxIntervalMs(config.getRetryMaxIntervalMs())
                .circuitBreakerFailureThreshold(config.getCircuitBreakerFailureThreshold())
                .circuitBreakerResetTimeoutSeconds(config.getCircuitBreakerResetTimeoutSeconds())
                .suppressMirrorActionsTriggers(config.isSuppressMirrorActionsTriggers())
                .loggingSink(config.getLoggingSink() != null ? config.getLoggingSink() : "CONSOLE")
                .loggingLevel(config.getLoggingLevel() != null ? config.getLoggingLevel() : "INFO")
                .splunkHecUrl(config.getSplunkHecUrl())
                .splunkHecTokenMasked(tokenMasked)
                .hasSplunkHecToken(hasToken)
                .splunkIndex(config.getSplunkIndex())
                .splunkSourceType(config.getSplunkSourceType())
                .logstashHost(config.getLogstashHost())
                .syslogHost(config.getSyslogHost())
                .rollingFilePath(config.getRollingFilePath())
                .jsonStructuredEnabled(config.isJsonStructuredEnabled())
                .circuitBreakerState(circuitBreakerManager.getState().name())
                .currentConsecutiveFailures(circuitBreakerManager.getCurrentConsecutiveFailures().get())
                .lastStateTransitionAt(circuitBreakerManager.getLastStateTransitionAt())
                .lastProbeMessage(circuitBreakerManager.getLastProbeMessage())
                .lastProbeSuccess(circuitBreakerManager.isLastProbeSuccess())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
