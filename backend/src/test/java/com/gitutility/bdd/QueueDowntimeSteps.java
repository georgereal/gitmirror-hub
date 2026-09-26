package com.gitutility.bdd;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SimulationConfigRequest;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.service.CircuitBreakerManagerService;
import com.gitutility.service.ConsumerRuntimeRegistry;
import com.gitutility.service.DlqRedriveService;
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
import com.gitutility.service.WebSocketNotificationService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class QueueDowntimeSteps {

    private static final String EXCHANGE = "git.sync.exchange";
    private static final String INCREMENTAL_KEY = "git.sync.incremental.key";

    private SyncJobRepository syncJobRepository;
    private RepoMappingRepository mappingRepository;
    private QueueProducerService queueProducer;
    private SimulationService simulation;
    private QueueConsumerService consumer;
    private SyncJob job;
    private RabbitTemplate rabbit;
    private MessageConverter converter;

    @Before("@queue-dlq")
    public void reset() {
        syncJobRepository = mock(SyncJobRepository.class);
        mappingRepository = mock(RepoMappingRepository.class);
        queueProducer = mock(QueueProducerService.class);
        RepoDirLockService locks = mock(RepoDirLockService.class);
        lenient().when(locks.lockFor(any())).thenAnswer(invocation -> new ReentrantLock());
        lenient().when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(mappingRepository.findById("1")).thenReturn(Optional.of(RepoMapping.builder()
                .id("1")
                .name("buffered")
                .repoAUrl("https://github.com/acme/origin.git")
                .repoBUrl("https://github.com/acme/mirror.git")
                .build()));
        lenient().when(mappingRepository.save(any(RepoMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));
        simulation = new SimulationService(
                mock(ObjectProvider.class),
                mappingRepository,
                syncJobRepository,
                mock(WebSocketNotificationService.class),
                mock(SyncEventBus.class),
                queueProducer,
                mock(ObjectProvider.class));
        consumer = new QueueConsumerService(
                mock(GitSyncEngine.class),
                syncJobRepository,
                mappingRepository,
                simulation,
                mock(WebSocketNotificationService.class),
                mock(PullRequestSyncService.class),
                mock(ReleaseAndStatusSyncService.class),
                mock(CircuitBreakerManagerService.class),
                mock(ProviderRateMeter.class),
                mock(JobCancellationService.class),
                mock(SyncJobService.class),
                new ConsumerRuntimeRegistry(),
                mock(SyncCheckpointService.class),
                mock(HubMetrics.class),
                mock(JobExecutionStateService.class),
                mock(PairMirrorSnapshotService.class),
                mock(PairDiffSnapshotService.class),
                mock(PairLeaseService.class),
                mock(InstanceIdentity.class),
                queueProducer,
                mock(ScmInstallationKeyResolver.class),
                mock(PairCatchupLedger.class),
                locks);
        ReflectionTestUtils.setField(consumer, "messagingProvider", "none");
        ReflectionTestUtils.setField(consumer, "webhookBusProvider", "off");
        rabbit = mock(RabbitTemplate.class);
        converter = mock(MessageConverter.class);
        job = null;
    }

    @Given("the job destination is simulated down")
    public void destinationDown() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateTargetDown(true)
                .simulateSourceDown(false)
                .build());
    }

    @Given("a sync job has used its last attempt")
    public void lastAttempt() {
        job = SyncJob.builder()
                .id("job-1")
                .mappingId("1")
                .status(SyncStatus.QUEUED)
                .attemptCount(2)
                .maxAttempts(3)
                .ref("refs/heads/main")
                .branch("main")
                .build();
        when(syncJobRepository.findById("job-1")).thenReturn(Optional.of(job));
    }

    @Given("a job is {string} for ref {string}")
    public void jobForRef(String status, String ref) {
        job = SyncJob.builder()
                .id("job-dlq")
                .mappingId("1")
                .status(SyncStatus.valueOf(status))
                .ref(ref)
                .branch("main")
                .build();
        when(syncJobRepository.findById("job-dlq")).thenReturn(Optional.of(job));
    }

    @Given("the destination outage simulation is turned off")
    public void outageOff() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateTargetDown(false)
                .simulateSourceDown(false)
                .build());
    }

    @When("that job fails with the destination outage")
    public void jobFails() throws Exception {
        try {
            consumer.consumeSyncEvent(SyncEventMessage.builder()
                    .jobId(job.getId())
                    .mappingId("1")
                    .ref(job.getRef())
                    .branch(job.getBranch())
                    .build());
        } catch (RuntimeException expected) {
            // The last attempt is rethrown so a broker can dead-letter it. This bus does not requeue.
        }
    }

    @When("the operator redrives the dead letter queue")
    public void redrive() {
        SyncEventMessage event = SyncEventMessage.builder()
                .jobId(job.getId())
                .mappingId("1")
                .ref(job.getRef())
                .branch(job.getBranch())
                .build();
        var raw = MessageBuilder.withBody(new byte[] {1}).build();
        when(rabbit.receive(eq("git.sync.dlq"), anyLong())).thenReturn(raw, (org.springframework.amqp.core.Message) null);
        when(converter.fromMessage(raw)).thenReturn(event);
        DlqRedriveService redrive = new DlqRedriveService(
                rabbit, converter, syncJobRepository, mock(WebSocketNotificationService.class));
        ReflectionTestUtils.setField(redrive, "mainExchangeName", EXCHANGE);
        ReflectionTestUtils.setField(redrive, "mainRoutingKey", "git.sync.key");
        ReflectionTestUtils.setField(redrive, "incrementalRoutingKey", INCREMENTAL_KEY);
        ReflectionTestUtils.setField(redrive, "dlqQueueName", "git.sync.dlq");
        redrive.redriveAllDlqMessages();
    }

    @Then("the job status is {string}")
    public void jobStatus(String status) {
        assertEquals(SyncStatus.valueOf(status), job.getStatus());
    }

    @Then("the message is not requeued on the execution lane")
    public void notRequeued() {
        verify(queueProducer, never()).republishSyncEvent(any());
    }

    @Then("the job is queued on the incremental lane")
    public void queuedOnIncrementalLane() {
        assertEquals(SyncStatus.QUEUED, job.getStatus());
        assertEquals(TriggerType.DLQ_REDRIVE, job.getTriggerType());
        verify(rabbit).convertAndSend(eq(EXCHANGE), eq(INCREMENTAL_KEY), any(SyncEventMessage.class));
    }
}
