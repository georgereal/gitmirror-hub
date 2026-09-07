package com.gitutility.service;

import com.gitutility.model.dto.SystemEngineConfigRequest;
import com.gitutility.model.dto.SystemEngineConfigResponse;
import com.gitutility.model.entity.SystemEngineConfig;
import com.gitutility.repository.SystemEngineConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SystemEngineConfigAndCircuitBreakerTest {

    @Mock
    private SystemEngineConfigRepository configRepository;

    @Mock
    private SimulationService simulationService;

    @Mock
    private WebSocketNotificationService webSocketNotificationService;

    @Mock
    private StorageTieringService storageTieringService;

    @Mock
    private QueueConsumerService queueConsumerService;

    @Mock
    private EnterpriseLoggingService enterpriseLoggingService;

    @Mock
    private ClusterRuntimeService clusterRuntimeService;

    private CircuitBreakerManagerService circuitBreakerManager;
    private SystemEngineConfigService systemEngineConfigService;

    @BeforeEach
    void setUp() {
        circuitBreakerManager = new CircuitBreakerManagerService(
                simulationService,
                webSocketNotificationService,
                clusterRuntimeService
        );
        ReflectionTestUtils.setField(circuitBreakerManager, "failureThreshold", 3);
        ReflectionTestUtils.setField(circuitBreakerManager, "resetTimeoutSeconds", 15);

        systemEngineConfigService = new SystemEngineConfigService(
                configRepository,
                circuitBreakerManager,
                storageTieringService,
                queueConsumerService,
                enterpriseLoggingService
        );

        ReflectionTestUtils.setField(systemEngineConfigService, "defaultLocalDir", "/tmp/local-test");
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultNasDir", "/tmp/nas-test");
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultMaxDiskQuotaMb", 20480L);
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultMaxCachedRepos", 500);
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultRetentionHours", 48);
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultMaxConcurrentPushes", 4);
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultMetadataSyncIntervalSeconds", 20);
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultCircuitBreakerFailureThreshold", 3);
        ReflectionTestUtils.setField(systemEngineConfigService, "defaultCircuitBreakerResetTimeoutSeconds", 15);
    }

    @Test
    void testCircuitBreakerTripsAfterThresholdFailures() {
        assertEquals(CircuitBreakerManagerService.CircuitState.CLOSED, circuitBreakerManager.getState());

        circuitBreakerManager.recordPermanentFailure("Network timeout 1");
        assertEquals(CircuitBreakerManagerService.CircuitState.CLOSED, circuitBreakerManager.getState());
        assertEquals(1, circuitBreakerManager.getCurrentConsecutiveFailures().get());

        circuitBreakerManager.recordPermanentFailure("Network timeout 2");
        assertEquals(CircuitBreakerManagerService.CircuitState.CLOSED, circuitBreakerManager.getState());
        assertEquals(2, circuitBreakerManager.getCurrentConsecutiveFailures().get());

        circuitBreakerManager.recordPermanentFailure("Network timeout 3");
        assertEquals(CircuitBreakerManagerService.CircuitState.OPEN, circuitBreakerManager.getState());
        verify(simulationService, times(1)).pauseConsumer();
    }

    @Test
    void testCircuitBreakerResetsOnSuccess() {
        circuitBreakerManager.recordPermanentFailure("Transient error");
        assertEquals(1, circuitBreakerManager.getCurrentConsecutiveFailures().get());

        circuitBreakerManager.recordSuccess();
        assertEquals(0, circuitBreakerManager.getCurrentConsecutiveFailures().get());
        assertEquals(CircuitBreakerManagerService.CircuitState.CLOSED, circuitBreakerManager.getState());
    }

    @Test
    void testCircuitBreakerForceReset() {
        circuitBreakerManager.recordPermanentFailure("Fail 1");
        circuitBreakerManager.recordPermanentFailure("Fail 2");
        circuitBreakerManager.recordPermanentFailure("Fail 3");
        assertEquals(CircuitBreakerManagerService.CircuitState.OPEN, circuitBreakerManager.getState());

        CircuitBreakerManagerService.ProbeResult result = circuitBreakerManager.probeAndReset(true);
        assertTrue(result.success());
        assertEquals(CircuitBreakerManagerService.CircuitState.CLOSED, circuitBreakerManager.getState());
        assertEquals(0, circuitBreakerManager.getCurrentConsecutiveFailures().get());
        verify(simulationService, atLeastOnce()).resumeConsumer();
    }

    @Test
    void testSystemEngineConfigUpdateAndHotReload() {
        SystemEngineConfig initialConfig = SystemEngineConfig.builder()
                .id(1L)
                .localDir("/tmp/local-test")
                .nasDir("/tmp/nas-test")
                .maxDiskQuotaMb(20480L)
                .maxCachedRepos(500)
                .retentionHours(48)
                .maxConcurrentPushes(4)
                .metadataSyncIntervalSeconds(20)
                .maxRetryAttempts(3)
                .retryInitialIntervalMs(3000L)
                .retryMultiplier(2.0)
                .retryMaxIntervalMs(30000L)
                .circuitBreakerFailureThreshold(3)
                .circuitBreakerResetTimeoutSeconds(15)
                .updatedAt(Instant.now())
                .build();

        when(configRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(initialConfig));
        when(configRepository.save(any(SystemEngineConfig.class))).thenAnswer(i -> i.getArgument(0));

        SystemEngineConfigRequest updateReq = SystemEngineConfigRequest.builder()
                .maxConcurrentPushes(10)
                .nasDir("/mnt/nas/git-mirrors")
                .maxRetryAttempts(5)
                .retryInitialIntervalMs(4000L)
                .build();

        SystemEngineConfigResponse resp = systemEngineConfigService.updateConfig(updateReq);

        assertEquals(10, resp.getMaxConcurrentPushes());
        assertEquals("/mnt/nas/git-mirrors", resp.getNasDir());
        assertEquals(5, resp.getMaxRetryAttempts());
        assertEquals(4000L, resp.getRetryInitialIntervalMs());

        verify(storageTieringService, times(1)).updateStorageParameters(
                eq("/tmp/local-test"),
                eq("/mnt/nas/git-mirrors"),
                eq(20480L),
                eq(500),
                eq(48)
        );
        verify(queueConsumerService, times(1)).updateOperationalParameters(eq(10), eq(20));
    }
}
