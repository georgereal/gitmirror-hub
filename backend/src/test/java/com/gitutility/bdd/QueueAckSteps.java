package com.gitutility.bdd;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.service.CircuitBreakerManagerService;
import com.gitutility.service.ConsumerRuntimeRegistry;
import com.gitutility.service.GitSyncEngine;
import com.gitutility.service.HubMetrics;
import com.gitutility.service.InstanceIdentity;
import com.gitutility.service.JobCancellationService;
import com.gitutility.service.JobExecutionStateService;
import com.gitutility.service.PairCatchupLedger;
import com.gitutility.service.PairDiffSnapshotService;
import com.gitutility.service.PairLeaseService;
import com.gitutility.service.PairMirrorSnapshotService;
import com.gitutility.service.ProviderRateMeter;
import com.gitutility.service.PullRequestSyncService;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.ReleaseAndStatusSyncService;
import com.gitutility.service.RepoDirLockService;
import com.gitutility.service.ScmInstallationKeyResolver;
import com.gitutility.service.SimulationService;
import com.gitutility.service.SyncCheckpointService;
import com.gitutility.service.SyncJobService;
import com.gitutility.service.SyncLaneRouter;
import com.gitutility.service.WebSocketNotificationService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class QueueAckSteps {

    private GitSyncEngine gitSyncEngine;
    private SyncJobRepository syncJobRepository;
    private QueueConsumerService consumer;
    private ConsumerRuntimeRegistry registry;
    private String jobId;
    private String pairName;

    @Before("@queue")
    public void reset() {
        gitSyncEngine = mock(GitSyncEngine.class);
        syncJobRepository = mock(SyncJobRepository.class);
        RepoDirLockService repoDirLockService = mock(RepoDirLockService.class);
        lenient().when(repoDirLockService.lockFor(any())).thenAnswer(inv -> new ReentrantLock());
        registry = new ConsumerRuntimeRegistry();
        consumer = new QueueConsumerService(
                gitSyncEngine,
                syncJobRepository,
                mock(RepoMappingRepository.class),
                mock(SimulationService.class),
                mock(WebSocketNotificationService.class),
                mock(PullRequestSyncService.class),
                mock(ReleaseAndStatusSyncService.class),
                mock(CircuitBreakerManagerService.class),
                mock(ProviderRateMeter.class),
                mock(JobCancellationService.class),
                mock(SyncJobService.class),
                registry,
                mock(SyncCheckpointService.class),
                mock(HubMetrics.class),
                mock(JobExecutionStateService.class),
                mock(PairMirrorSnapshotService.class),
                mock(PairDiffSnapshotService.class),
                mock(PairLeaseService.class),
                mock(InstanceIdentity.class),
                mock(QueueProducerService.class),
                mock(ScmInstallationKeyResolver.class),
                mock(PairCatchupLedger.class),
                repoDirLockService);
        jobId = null;
        pairName = null;
    }

    @Given("job {string} for pair {string} is cancelled")
    public void cancelledJob(String id, String pair) {
        jobId = id;
        pairName = pair;
        when(syncJobRepository.findById(id)).thenReturn(Optional.of(SyncJob.builder()
                .id(id)
                .pairName(pair)
                .status(SyncStatus.CANCELLED)
                .build()));
    }

    @Given("job {string} does not exist")
    public void missingJob(String id) {
        jobId = id;
        pairName = "gone";
        when(syncJobRepository.findById(id)).thenReturn(Optional.empty());
    }

    @When("the consumer receives that job")
    public void consume() {
        SyncEventMessage event = SyncEventMessage.builder()
                .jobId(jobId)
                .mappingId("1")
                .pairName(pairName)
                .ref("refs/heads/main")
                .build();
        assertDoesNotThrow(() -> consumer.consumeSyncEvent(event));
    }

    @Then("git sync does not run")
    public void gitDoesNotRun() throws Exception {
        verify(gitSyncEngine, never()).executeSync(any());
    }

    @Then("the incremental lane has no unacked work")
    public void noUnacked() {
        assertEquals(0, registry.unackedCount(SyncLaneRouter.LANE_INCREMENTAL));
    }
}
