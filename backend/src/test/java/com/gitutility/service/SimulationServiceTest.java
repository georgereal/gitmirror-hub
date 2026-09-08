package com.gitutility.service;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SimulationConfigRequest;
import com.gitutility.model.dto.SyntheticWebhookRequest;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock
    private ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistryProvider;
    @Mock
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Mock
    private RepoMappingRepository mappingRepository;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private WebSocketNotificationService webSocketNotificationService;
    @Mock
    private SyncEventBus syncEventBus;
    @Mock
    private QueueProducerService queueProducerService;

    private SimulationService simulationService;

    @BeforeEach
    void setUp() {
        lenient().when(listenerRegistryProvider.getIfAvailable()).thenReturn(listenerRegistry);
        simulationService = new SimulationService(
                listenerRegistryProvider,
                mappingRepository,
                syncJobRepository,
                webSocketNotificationService,
                syncEventBus,
                queueProducerService
        );
    }

    @Test
    void testPauseAndResumeConsumerFlags() {
        assertFalse(simulationService.isConsumerPaused());

        simulationService.pauseConsumer();
        assertTrue(simulationService.isConsumerPaused());

        simulationService.resumeConsumer();
        assertFalse(simulationService.isConsumerPaused());
    }

    @Test
    void pauseAndResumeOperateOnBothExecutionListeners() {
        MessageListenerContainer full = mock(MessageListenerContainer.class);
        MessageListenerContainer incremental = mock(MessageListenerContainer.class);
        when(full.isRunning()).thenReturn(true, false);
        when(incremental.isRunning()).thenReturn(true, false);
        when(listenerRegistry.getListenerContainer(SyncLaneRouter.FULL_CONSUMER_ID)).thenReturn(full);
        when(listenerRegistry.getListenerContainer(SyncLaneRouter.INCREMENTAL_CONSUMER_ID)).thenReturn(incremental);
        lenient().when(listenerRegistry.getListenerContainer(SyncLaneRouter.LEGACY_CONSUMER_ID)).thenReturn(null);

        simulationService.pauseConsumer();
        verify(full).stop();
        verify(incremental).stop();
        assertTrue(simulationService.isConsumerPaused());

        simulationService.resumeConsumer();
        verify(full).start();
        verify(incremental).start();
        assertFalse(simulationService.isConsumerPaused());
    }

    @Test
    void testInjectedFaultsTriggerExceptions() {
        // No faults enabled initially
        assertDoesNotThrow(() -> simulationService.checkInjectedFaults());

        // Target down simulation
        simulationService.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateTargetDown(true)
                .build());
        Exception targetDownEx = assertThrows(RuntimeException.class, () -> simulationService.checkInjectedFaults());
        assertTrue(targetDownEx.getMessage().contains("Destination repository is UNREACHABLE"));

        // Source down simulation
        simulationService.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateTargetDown(false)
                .simulateSourceDown(true)
                .build());
        Exception srcDownEx = assertThrows(RuntimeException.class, () -> simulationService.checkInjectedFaults());
        assertTrue(srcDownEx.getMessage().contains("Origin repository is DOWN"));
    }

    @Test
    void testEmitSyntheticWebhook() {
        RepoMapping mapping = RepoMapping.builder()
                .id(1L)
                .name("test-pair")
                .repoAUrl("https://github.com/a/repo-a.git")
                .repoBUrl("https://github.com/b/repo-b.git")
                .build();

        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(invocation -> {
            SyncJob job = invocation.getArgument(0);
            job.setId(99L);
            return job;
        });

        SyntheticWebhookRequest req = SyntheticWebhookRequest.builder()
                .mappingId(1L)
                .branch("feature/test-queue")
                .commitSha("abcdef123")
                .commitMessage("test commit")
                .authorName("Dev Tester")
                .build();

        SyncJob emittedJob = simulationService.emitSyntheticWebhook(req);

        assertNotNull(emittedJob);
        assertEquals(99L, emittedJob.getId());
        assertEquals(SyncStatus.QUEUED, emittedJob.getStatus());
        assertEquals(TriggerType.SYNTHETIC, emittedJob.getTriggerType());
        assertEquals("abcdef123", emittedJob.getCommitSha());

        verify(queueProducerService, times(1)).enqueueSyncJob(
                eq(mapping),
                any(SyncJob.class),
                eq("https://github.com/a/repo-a.git"),
                eq("https://github.com/b/repo-b.git"),
                eq("refs/heads/feature/test-queue"),
                eq("feature/test-queue"),
                isNull(),
                eq("abcdef123"),
                eq("test commit"),
                eq("Dev Tester"),
                eq(TriggerType.SYNTHETIC)
        );
    }
}
