package com.gitutility.coverage;

import com.gitutility.messaging.MessagingModule;
import com.gitutility.messaging.MessagingProvider;
import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.PeerStatusResponse;
import com.gitutility.model.dto.RuntimeMetricsResponse;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.repository.InstanceHeartbeatRepository;
import com.gitutility.service.BulkMirrorService;
import com.gitutility.service.FailoverService;
import com.gitutility.service.GitComparisonService;
import com.gitutility.service.GitLfsSyncService;
import com.gitutility.service.InstanceHeartbeatService;
import com.gitutility.service.InstanceIdentity;
import com.gitutility.service.MetadataSyncSettingsService;
import com.gitutility.service.PairDiffSnapshotService;
import com.gitutility.service.PullRequestSyncService;
import com.gitutility.service.QueueObservabilityService;
import com.gitutility.service.ReleaseAndStatusSyncService;
import com.gitutility.service.ReplicaRulesetService;
import com.gitutility.service.RepoMappingService;
import com.gitutility.service.RuntimeMetricsService;
import com.gitutility.service.SimulationService;
import com.gitutility.service.StorageTieringService;
import com.gitutility.service.SyncConflictService;
import com.gitutility.service.SyncJobService;
import com.gitutility.service.WebSocketNotificationService;
import com.gitutility.service.WriteAuthorityService;
import com.gitutility.controller.QueueController;
import com.gitutility.controller.RepoMappingController;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("coverage")
class EdgeCoverageTest {

    @Test
    void mappingAndQueueControllersRun() {
        RepoMappingService mappings = mock(RepoMappingService.class);
        WriteAuthorityService authority = mock(WriteAuthorityService.class);
        FailoverService failover = mock(FailoverService.class);
        when(mappings.getAllMappings()).thenReturn(List.of());
        when(authority.notesFor(any())).thenReturn(Map.of());
        when(mappings.getMappingById("missing")).thenReturn(Optional.empty());
        when(failover.status("lane")).thenReturn(PeerStatusResponse.builder().phase("STEADY").build());
        RepoMappingController mappingsApi = new RepoMappingController(
                mappings,
                mock(BulkMirrorService.class),
                mock(GitComparisonService.class),
                mock(PullRequestSyncService.class),
                mock(GitLfsSyncService.class),
                mock(ReleaseAndStatusSyncService.class),
                mock(StorageTieringService.class),
                mock(SyncConflictService.class),
                mock(PairDiffSnapshotService.class),
                mock(ReplicaRulesetService.class),
                authority,
                mock(MetadataSyncSettingsService.class),
                failover);
        assertEquals(200, mappingsApi.getAllMappings().getStatusCode().value());
        assertEquals(404, mappingsApi.getMappingById("missing").getStatusCode().value());
        mappingsApi.deleteMapping("lane");
        assertEquals("STEADY", mappingsApi.peerStatus("lane").getBody().getPhase());

        MessagingModule messaging = new MessagingModule();
        ReflectionTestUtils.setField(messaging, "providerProperty", MessagingProvider.NONE.wireId());
        @SuppressWarnings("unchecked")
        ObjectProvider<com.gitutility.service.DlqRedriveService> dlq = mock(ObjectProvider.class);
        when(dlq.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConnectionFactory> connections = mock(ObjectProvider.class);
        QueueController queue = new QueueController(
                dlq,
                mock(SimulationService.class),
                connections,
                mock(SyncJobService.class),
                mock(QueueObservabilityService.class),
                messaging,
                mock(SyncEventBus.class));
        ReflectionTestUtils.setField(queue, "mainQueueName", "git.sync.queue");
        ReflectionTestUtils.setField(queue, "incrementalQueueName", "git.sync.incremental.queue");
        ReflectionTestUtils.setField(queue, "inboundQueueName", "git.sync.inbound.queue");
        ReflectionTestUtils.setField(queue, "dlqQueueName", "git.sync.dlq");
        ReflectionTestUtils.setField(queue, "brokerAddress", "localhost");
        assertEquals(200, queue.getQueueStatus().getStatusCode().value());
        assertNotNull(queue.redriveDlq().getBody());
    }

    @Test
    void websocketBroadcastAndHeartbeatWriteRun() {
        WebSocketNotificationService sockets = new WebSocketNotificationService(mock(SimpMessagingTemplate.class));
        sockets.notifyPairSnapshot("lane");
        sockets.notifyJobUpdated(SyncJob.builder().id("job-1").mappingId("lane").build());
        sockets.notifyQueueUpdated(Map.of("pending", 0));
        sockets.notifySimulationUpdated(Map.of("paused", false));
        sockets.notifyJobProgress("job-1", "lane", "fetch", "source", 1, 2, "reading");

        InstanceIdentity identity = new InstanceIdentity();
        ReflectionTestUtils.setField(identity, "instanceId", "pod-a");
        RuntimeMetricsService metrics = mock(RuntimeMetricsService.class);
        when(metrics.snapshot()).thenReturn(RuntimeMetricsResponse.builder().build());
        InstanceHeartbeatRepository heartbeats = mock(InstanceHeartbeatRepository.class);
        when(heartbeats.findAll()).thenReturn(List.of());
        InstanceHeartbeatService beats = new InstanceHeartbeatService(
                heartbeats, metrics, identity, JsonMapper.builder().build());
        ReflectionTestUtils.setField(beats, "staleSeconds", 15);
        beats.publishHeartbeat();
        assertNotNull(beats.clusterSnapshot());
    }
}
