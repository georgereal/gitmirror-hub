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
    private final UnmappedWebhookRetention unmappedWebhookRetention;

    /** Paths previously seeded from code defaults. They are not an operator choice. */
    private static final String BUILTIN_LOCAL_DIR = "/tmp/git-utility-mirrors";
    private static final String BUILTIN_NAS_DIR = "/tmp/git-utility-nas-mirrors";

    @Value("${git-utility.storage.max-disk-quota-mb:51200}")
    private long defaultMaxDiskQuotaMb;

    @Value("${git-utility.storage.max-cached-repos:1000}")
    private int defaultMaxCachedRepos;

    @Value("${git-utility.storage.retention-hours:72}")
    private int defaultRetentionHours;

    @Value("${git-utility.storage.unmapped-webhook-ttl-days:7}")
    private int defaultUnmappedWebhookTtlDays;

    @Value("${git-utility.storage.unmapped-webhook-purge-days:7}")
    private int defaultUnmappedWebhookPurgeDays;

    @Value("${git-utility.storage.unmapped-webhook-purge-interval-minutes:60}")
    private int defaultUnmappedWebhookPurgeIntervalMinutes;

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
            @Lazy EnterpriseLoggingService enterpriseLoggingService,
            @Lazy UnmappedWebhookRetention unmappedWebhookRetention) {
        this.configRepository = configRepository;
        this.circuitBreakerManager = circuitBreakerManager;
        this.storageTieringService = storageTieringService;
        this.queueConsumerService = queueConsumerService;
        this.enterpriseLoggingService = enterpriseLoggingService;
        this.unmappedWebhookRetention = unmappedWebhookRetention;
    }

    @PostConstruct
    public void init() {
        SystemEngineConfig config = fillWebhookRetention(syncStoragePathsFromEnv(getOrCreateConfig()));
        applyHotReload(config);
    }

    @Transactional
    public SystemEngineConfig getOrCreateConfig() {
        return configRepository.findTopByOrderByIdAsc().orElseGet(() -> {
            log.info("Seeding initial SystemEngineConfig in database");
            SystemEngineConfig config = SystemEngineConfig.builder()
                    .localDir(orEmpty(explicitEnv("GIT_STORAGE_LOCAL_DIR", "GIT_WORKSPACE_DIR")))
                    .nasDir(orEmpty(explicitEnv("GIT_STORAGE_NAS_DIR")))
                    .maxDiskQuotaMb(defaultMaxDiskQuotaMb)
                    .maxCachedRepos(defaultMaxCachedRepos)
                    .retentionHours(defaultRetentionHours)
                    .unmappedWebhookTtlDays(Math.max(1, defaultUnmappedWebhookTtlDays))
                    .unmappedWebhookPurgeDays(clampPurgeDays(defaultUnmappedWebhookPurgeDays, Math.max(1, defaultUnmappedWebhookTtlDays)))
                    .unmappedWebhookPurgeIntervalMinutes(Math.max(1, defaultUnmappedWebhookPurgeIntervalMinutes))
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

        if (req.getLocalDir() != null) {
            config.setLocalDir(req.getLocalDir().trim());
        }
        if (req.getNasDir() != null) {
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
        int ttlBefore = config.getUnmappedWebhookTtlDays();
        if (req.getUnmappedWebhookTtlDays() != null && req.getUnmappedWebhookTtlDays() >= 1) {
            config.setUnmappedWebhookTtlDays(Math.min(3650, req.getUnmappedWebhookTtlDays()));
        }
        if (req.getUnmappedWebhookPurgeDays() != null && req.getUnmappedWebhookPurgeDays() >= 1) {
            config.setUnmappedWebhookPurgeDays(req.getUnmappedWebhookPurgeDays());
        }
        if (req.getUnmappedWebhookPurgeIntervalMinutes() != null && req.getUnmappedWebhookPurgeIntervalMinutes() >= 1) {
            config.setUnmappedWebhookPurgeIntervalMinutes(Math.min(10_080, req.getUnmappedWebhookPurgeIntervalMinutes()));
        }
        if (config.getUnmappedWebhookPurgeDays() > config.getUnmappedWebhookTtlDays()) {
            throw new IllegalArgumentException("Purge age cannot exceed the webhook TTL ceiling");
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
        if (saved.getUnmappedWebhookTtlDays() != ttlBefore && unmappedWebhookRetention != null) {
            unmappedWebhookRetention.rewriteStamped();
        }

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

    /**
     * An explicit storage env var is written onto the config row so the settings page loads it
     * from the database. A missing env leaves a blank path, including when the row still holds
     * the old built-in {@code /tmp} default.
     */
    private SystemEngineConfig syncStoragePathsFromEnv(SystemEngineConfig config) {
        boolean changed = false;
        String envLocal = explicitEnv("GIT_STORAGE_LOCAL_DIR", "GIT_WORKSPACE_DIR");
        if (envLocal != null) {
            if (!envLocal.equals(config.getLocalDir())) {
                config.setLocalDir(envLocal);
                changed = true;
            }
        } else if (config.getLocalDir() == null || config.getLocalDir().isBlank()
                || BUILTIN_LOCAL_DIR.equals(config.getLocalDir().trim())) {
            if (config.getLocalDir() != null && !config.getLocalDir().isEmpty()) {
                config.setLocalDir("");
                changed = true;
            }
        }
        String envNas = explicitEnv("GIT_STORAGE_NAS_DIR");
        if (envNas != null) {
            if (!envNas.equals(config.getNasDir())) {
                config.setNasDir(envNas);
                changed = true;
            }
        } else if (config.getNasDir() == null || config.getNasDir().isBlank()
                || BUILTIN_NAS_DIR.equals(config.getNasDir().trim())) {
            if (config.getNasDir() != null && !config.getNasDir().isEmpty()) {
                config.setNasDir("");
                changed = true;
            }
        }
        if (!changed) {
            return config;
        }
        config.setUpdatedAt(Instant.now());
        log.info("Stored storage paths from environment: local='{}', nas='{}'",
                config.getLocalDir(), config.getNasDir());
        return configRepository.save(config);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** First non-blank environment variable among {@code names}. Null when none are set. */
    private static String explicitEnv(String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
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
                .unmappedWebhookTtlDays(config.getUnmappedWebhookTtlDays())
                .unmappedWebhookPurgeDays(config.getUnmappedWebhookPurgeDays())
                .unmappedWebhookPurgeIntervalMinutes(config.getUnmappedWebhookPurgeIntervalMinutes())
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

    public int unmappedWebhookTtlDays() {
        return Math.max(1, getOrCreateConfig().getUnmappedWebhookTtlDays());
    }

    public int unmappedWebhookPurgeDays() {
        SystemEngineConfig config = getOrCreateConfig();
        return clampPurgeDays(config.getUnmappedWebhookPurgeDays(), Math.max(1, config.getUnmappedWebhookTtlDays()));
    }

    public int unmappedWebhookPurgeIntervalMinutes() {
        return Math.max(1, getOrCreateConfig().getUnmappedWebhookPurgeIntervalMinutes());
    }

    private SystemEngineConfig fillWebhookRetention(SystemEngineConfig config) {
        boolean changed = false;
        int ttl = config.getUnmappedWebhookTtlDays();
        if (ttl <= 0) {
            ttl = Math.max(1, defaultUnmappedWebhookTtlDays);
            config.setUnmappedWebhookTtlDays(ttl);
            changed = true;
        }
        int purge = config.getUnmappedWebhookPurgeDays();
        if (purge <= 0) {
            purge = clampPurgeDays(defaultUnmappedWebhookPurgeDays, ttl);
            config.setUnmappedWebhookPurgeDays(purge);
            changed = true;
        } else if (purge > ttl) {
            config.setUnmappedWebhookPurgeDays(ttl);
            changed = true;
        }
        if (config.getUnmappedWebhookPurgeIntervalMinutes() <= 0) {
            config.setUnmappedWebhookPurgeIntervalMinutes(Math.max(1, defaultUnmappedWebhookPurgeIntervalMinutes));
            changed = true;
        }
        if (!changed) {
            return config;
        }
        config.setUpdatedAt(Instant.now());
        log.info("Stored discarded-webhook retention from defaults: ttl={}d purge={}d interval={}m",
                config.getUnmappedWebhookTtlDays(),
                config.getUnmappedWebhookPurgeDays(),
                config.getUnmappedWebhookPurgeIntervalMinutes());
        return configRepository.save(config);
    }

    private static int clampPurgeDays(int purgeDays, int ttlDays) {
        int ttl = Math.max(1, ttlDays);
        int purge = purgeDays <= 0 ? ttl : purgeDays;
        return Math.min(purge, ttl);
    }
}
