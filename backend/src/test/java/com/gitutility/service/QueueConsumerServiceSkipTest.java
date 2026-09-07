package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueConsumerServiceSkipTest {

    @Mock private GitSyncEngine gitSyncEngine;
    @Mock private SyncJobRepository syncJobRepository;
    @Mock private RepoMappingRepository mappingRepository;
    @Mock private SimulationService simulationService;
    @Mock private WebSocketNotificationService webSocketNotificationService;
    @Mock private PullRequestSyncService pullRequestSyncService;
    @Mock private ReleaseAndStatusSyncService releaseAndStatusSyncService;
    @Mock private CircuitBreakerManagerService circuitBreakerManager;
    @Mock private ProviderRateMeter providerRateMeter;
    @Mock private JobCancellationService jobCancellationService;
    @Mock private SyncJobService syncJobService;
    @Mock private SyncCheckpointService syncCheckpointService;
    @Mock private HubMetrics hubMetrics;
    @Mock private JobExecutionStateService jobExecutionStateService;
    @Mock private PairMirrorSnapshotService pairMirrorSnapshotService;
    @Mock private PairDiffSnapshotService pairDiffSnapshotService;
    @Mock private PairLeaseService pairLeaseService;
    @Mock private InstanceIdentity instanceIdentity;
    @Mock private QueueProducerService queueProducerService;
    @Mock private ScmInstallationKeyResolver installationKeyResolver;


    private QueueConsumerService consumer;
    private ConsumerRuntimeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConsumerRuntimeRegistry();
        consumer = new QueueConsumerService(
                gitSyncEngine,
                syncJobRepository,
                mappingRepository,
                simulationService,
                webSocketNotificationService,
                pullRequestSyncService,
                releaseAndStatusSyncService,
                circuitBreakerManager,
                providerRateMeter,
                jobCancellationService,
                syncJobService,
                registry,
                syncCheckpointService,
                hubMetrics,
                jobExecutionStateService,
                pairMirrorSnapshotService,
                pairDiffSnapshotService,
                pairLeaseService,
                instanceIdentity,
                queueProducerService,
                installationKeyResolver

        );
    }

    @Test
    void cancelledJobIsSkippedWithoutThrowingSoAmqpAcks() throws Exception {
        SyncJob job = SyncJob.builder()
                .id(20L)
                .pairName("vscode")
                .status(SyncStatus.CANCELLED)
                .build();
        when(syncJobRepository.findById(20L)).thenReturn(Optional.of(job));

        SyncEventMessage event = SyncEventMessage.builder()
                .jobId(20L)
                .mappingId(1L)
                .pairName("vscode")
                .ref("refs/heads/main")
                .build();

        assertDoesNotThrow(() -> consumer.consumeSyncEvent(event));
        verify(gitSyncEngine, never()).executeSync(any());
        verify(circuitBreakerManager, never()).recordPermanentFailure(any());
        assertEquals(0, registry.unackedCount(SyncLaneRouter.LANE_INCREMENTAL));
    }

    @Test
    void missingJobIsSkippedWithoutThrowing() throws Exception {
        when(syncJobRepository.findById(99L)).thenReturn(Optional.empty());
        SyncEventMessage event = SyncEventMessage.builder().jobId(99L).pairName("gone").build();
        assertDoesNotThrow(() -> consumer.consumeSyncEvent(event));
        verify(gitSyncEngine, never()).executeSync(any());
    }

    @Test
    void incrementalBranchJobDoesNotRunPairMetadata() throws Exception {
        SyncJob job = SyncJob.builder()
                .id(21L)
                .pairName("pair")
                .status(SyncStatus.QUEUED)
                .attemptCount(0)
                .build();
        when(syncJobRepository.findById(21L)).thenReturn(Optional.of(job));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        GitSyncEngine.SyncResult result = new GitSyncEngine.SyncResult();
        result.success = true;
        result.pipeline = SyncPipelineState.initial();
        when(gitSyncEngine.executeSync(any())).thenReturn(result);
        when(mappingRepository.findById(1L)).thenReturn(Optional.empty());

        SyncEventMessage event = SyncEventMessage.builder()
                .jobId(21L)
                .mappingId(1L)
                .pairName("pair")
                .ref("refs/heads/main")
                .branch("main")
                .sourceRepoUrl("https://github.com/a/src.git")
                .targetRepoUrl("https://github.com/b/dst.git")
                .build();

        consumer.consumeSyncEvent(event);

        verify(pullRequestSyncService, never()).syncOpenPullRequests(any(), any(), any());
        verify(releaseAndStatusSyncService, never()).syncReleases(any(), any(), any());
    }

    @Test
    void fullMirrorJobRunsPairMetadata() throws Exception {
        SyncJob job = SyncJob.builder()
                .id(22L)
                .pairName("pair")
                .status(SyncStatus.QUEUED)
                .attemptCount(0)
                .build();
        when(syncJobRepository.findById(22L)).thenReturn(Optional.of(job));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        GitSyncEngine.SyncResult result = new GitSyncEngine.SyncResult();
        result.success = true;
        result.pipeline = SyncPipelineState.initial();
        when(gitSyncEngine.executeSync(any())).thenReturn(result);
        when(mappingRepository.findById(1L)).thenReturn(Optional.empty());
        when(pullRequestSyncService.syncOpenPullRequests(eq(1L), any(), any())).thenReturn(0);
        when(releaseAndStatusSyncService.syncReleases(eq(1L), any(), any())).thenReturn(0);

        SyncEventMessage event = SyncEventMessage.builder()
                .jobId(22L)
                .mappingId(1L)
                .pairName("pair")
                .ref(null)
                .branch("*")
                .sourceRepoUrl("https://github.com/a/src.git")
                .targetRepoUrl("https://github.com/b/dst.git")
                .build();

        consumer.consumeSyncEvent(event);

        verify(pullRequestSyncService).syncOpenPullRequests(eq(1L), any(), any(), eq(22L), any());
        verify(releaseAndStatusSyncService).syncReleases(eq(1L), any(), any(), any());
    }
}
