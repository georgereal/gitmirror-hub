package com.gitutility.service;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.QueueStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueObservabilityServiceTest {

    @Mock
    private SimulationService simulationService;
    @Mock
    private DlqRedriveService dlqRedriveService;
    @Mock
    private ObjectProvider<DlqRedriveService> dlqRedriveServiceProvider;
    @Mock
    private SyncEventBus syncEventBus;

    private ConsumerRuntimeRegistry registry;
    private QueueObservabilityService service;

    @BeforeEach
    void setUp() {
        registry = new ConsumerRuntimeRegistry();
        when(dlqRedriveServiceProvider.getIfAvailable()).thenReturn(dlqRedriveService);
        service = new QueueObservabilityService(registry, simulationService, dlqRedriveServiceProvider, syncEventBus);
        ReflectionTestUtils.setField(service, "mainQueueName", "git.sync.queue");
        ReflectionTestUtils.setField(service, "incrementalQueueName", "git.sync.incremental.queue");
        ReflectionTestUtils.setField(service, "inboundQueueName", "git.sync.inbound.queue");
    }

    @Test
    void snapshotIncludesReadyUnackedAndDeadVsPaused() {
        when(simulationService.isConsumerPaused()).thenReturn(false);
        when(simulationService.getListenerHealth(SyncLaneRouter.FULL_CONSUMER_ID))
                .thenReturn(new SimulationService.ListenerHealth(true, true, 1, 1, 5));
        when(simulationService.getListenerHealth(SyncLaneRouter.INCREMENTAL_CONSUMER_ID))
                .thenReturn(new SimulationService.ListenerHealth(true, false, 0, 1, 5));
        when(simulationService.getListenerHealth(SyncLaneRouter.INBOUND_CONSUMER_ID))
                .thenReturn(new SimulationService.ListenerHealth(true, true, 1, 1, 5));
        when(dlqRedriveService.getQueueDepth("git.sync.queue"))
                .thenReturn(new DlqRedriveService.QueueDepth(12, 1));
        when(dlqRedriveService.getQueueDepth("git.sync.incremental.queue"))
                .thenReturn(new DlqRedriveService.QueueDepth(3, 0));
        when(dlqRedriveService.getQueueDepth("git.sync.inbound.queue"))
                .thenReturn(new DlqRedriveService.QueueDepth(0, 1));

        registry.bind(SyncLaneRouter.LANE_FULL, SyncLaneRouter.FULL_CONSUMER_ID, 44L, "openmaic", null);

        List<QueueStatusResponse.ConsumerLaneStatus> lanes = service.snapshotLanes();
        QueueStatusResponse.ConsumerLaneStatus full = lanes.stream()
                .filter(l -> SyncLaneRouter.LANE_FULL.equals(l.getLane())).findFirst().orElseThrow();
        QueueStatusResponse.ConsumerLaneStatus incremental = lanes.stream()
                .filter(l -> SyncLaneRouter.LANE_INCREMENTAL.equals(l.getLane())).findFirst().orElseThrow();

        assertEquals(12, full.getReadyCount());
        assertEquals(1, full.getUnackedCount());
        assertEquals(44L, full.getCurrentWork().get(0).getJobId());
        assertFalse(full.isDead());
        assertEquals(0, full.getIdleThreads());

        assertEquals(3, incremental.getReadyCount());
        assertEquals(0, incremental.getUnackedCount());
        assertTrue(incremental.isDead());
        assertFalse(incremental.isPaused());
    }

    @Test
    void pausedListenerIsNotDead() {
        when(simulationService.isConsumerPaused()).thenReturn(true);
        when(simulationService.getListenerHealth(SyncLaneRouter.FULL_CONSUMER_ID))
                .thenReturn(new SimulationService.ListenerHealth(true, false, 0, 1, 5));
        when(simulationService.getListenerHealth(SyncLaneRouter.INCREMENTAL_CONSUMER_ID))
                .thenReturn(new SimulationService.ListenerHealth(true, false, 0, 1, 5));
        when(simulationService.getListenerHealth(SyncLaneRouter.INBOUND_CONSUMER_ID))
                .thenReturn(new SimulationService.ListenerHealth(true, true, 1, 1, 5));
        when(dlqRedriveService.getQueueDepth("git.sync.queue")).thenReturn(DlqRedriveService.QueueDepth.EMPTY);
        when(dlqRedriveService.getQueueDepth("git.sync.incremental.queue")).thenReturn(DlqRedriveService.QueueDepth.EMPTY);
        when(dlqRedriveService.getQueueDepth("git.sync.inbound.queue")).thenReturn(DlqRedriveService.QueueDepth.EMPTY);

        QueueStatusResponse.ConsumerLaneStatus full = service.snapshotLanes().stream()
                .filter(l -> SyncLaneRouter.LANE_FULL.equals(l.getLane())).findFirst().orElseThrow();
        assertTrue(full.isPaused());
        assertFalse(full.isDead());
    }
}
