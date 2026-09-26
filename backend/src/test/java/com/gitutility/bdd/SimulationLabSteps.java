package com.gitutility.bdd;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.messaging.none.NoneSyncEventBus;
import com.gitutility.messaging.webhook.WebhookBusControls;
import com.gitutility.model.dto.SimulationConfigRequest;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.dto.SyntheticWebhookRequest;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.SimulationService;
import com.gitutility.service.SyncLaneRouter;
import com.gitutility.service.WebSocketNotificationService;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SimulationLabSteps {

    private ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistryProvider;
    private RabbitListenerEndpointRegistry listenerRegistry;
    private RepoMappingRepository mappingRepository;
    private SyncJobRepository syncJobRepository;
    private WebSocketNotificationService webSocketNotificationService;
    private QueueProducerService queueProducerService;
    private ObjectProvider<WebhookBusControls> webhookBusControls;
    private MessageListenerContainer fullListener;
    private MessageListenerContainer incrementalListener;
    private SimulationService simulation;
    private Exception fault;
    private SyncJob syntheticJob;
    private NoneSyncEventBus noneBus;
    private QueueConsumerService noneConsumer;
    private String lastSha;
    private long delayStartedAt;

    @Before("@simulation")
    public void reset() {
        listenerRegistryProvider = mock(ObjectProvider.class);
        listenerRegistry = mock(RabbitListenerEndpointRegistry.class);
        mappingRepository = mock(RepoMappingRepository.class);
        syncJobRepository = mock(SyncJobRepository.class);
        webSocketNotificationService = mock(WebSocketNotificationService.class);
        queueProducerService = mock(QueueProducerService.class);
        webhookBusControls = mock(ObjectProvider.class);
        fullListener = null;
        incrementalListener = null;
        fault = null;
        syntheticJob = null;
        noneBus = null;
        noneConsumer = null;
        lastSha = null;
        delayStartedAt = 0L;
        simulation = service(mock(SyncEventBus.class));
    }

    @After("@simulation")
    public void stopNoneBus() {
        if (noneBus == null) {
            return;
        }
        try {
            ReflectionTestUtils.invokeMethod(noneBus, "shutdown");
        } finally {
            noneBus = null;
        }
    }

    @Given("in-memory messaging")
    public void inMemoryMessaging() {
        simulation = service(mock(NoneSyncEventBus.class));
    }

    @Given("rabbit messaging with no listener containers")
    public void rabbitWithoutContainers() {
        lenient().when(listenerRegistryProvider.getIfAvailable()).thenReturn(listenerRegistry);
        simulation = service(mock(SyncEventBus.class));
    }

    @Given("rabbit messaging with full and incremental listeners running")
    public void rabbitWithListeners() {
        fullListener = mock(MessageListenerContainer.class);
        incrementalListener = mock(MessageListenerContainer.class);
        when(fullListener.isRunning()).thenReturn(true, false, false, true);
        when(incrementalListener.isRunning()).thenReturn(true, false, false, true);
        when(listenerRegistry.getListenerContainer(SyncLaneRouter.FULL_CONSUMER_ID)).thenReturn(fullListener);
        when(listenerRegistry.getListenerContainer(SyncLaneRouter.INCREMENTAL_CONSUMER_ID)).thenReturn(incrementalListener);
        lenient().when(listenerRegistry.getListenerContainer(SyncLaneRouter.LEGACY_CONSUMER_ID)).thenReturn(null);
        lenient().when(listenerRegistryProvider.getIfAvailable()).thenReturn(listenerRegistry);
        simulation = service(mock(SyncEventBus.class));
    }

    @Given("the destination repository is simulated down")
    public void destinationDown() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateTargetDown(true)
                .simulateSourceDown(false)
                .build());
    }

    @Given("the origin repository is simulated down")
    public void originDown() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateTargetDown(false)
                .simulateSourceDown(true)
                .build());
    }

    @Given("a mirror pair {string} from {string} to {string}")
    public void mirrorPair(String name, String origin, String mirror) {
        RepoMapping mapping = RepoMapping.builder()
                .id("1")
                .name(name)
                .repoAUrl(origin)
                .repoBUrl(mirror)
                .build();
        when(mappingRepository.findById("1")).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(invocation -> {
            SyncJob job = invocation.getArgument(0);
            job.setId("99");
            return job;
        });
    }

    @When("the operator pauses consumers")
    public void pause() {
        simulation.pauseConsumer();
    }

    @When("the operator resumes consumers")
    public void resume() {
        simulation.resumeConsumer();
    }

    @When("a sync checks injected faults")
    public void checkFaults() {
        try {
            simulation.checkInjectedFaults();
            fault = null;
        } catch (Exception ex) {
            fault = ex;
        }
    }

    @Given("execution consumers are paused")
    public void consumersArePaused() {
        noneConsumer = mock(QueueConsumerService.class);
        AtomicReference<SimulationService> live = new AtomicReference<>();
        SimulationService probe = mock(SimulationService.class);
        lenient().when(probe.isConsumerPaused()).thenAnswer(invocation ->
                live.get() != null && live.get().isConsumerPaused());
        noneBus = new NoneSyncEventBus(noneConsumer, probe, new SimpleMeterRegistry(), 1);
        simulation = service(noneBus);
        live.set(simulation);
        mirrorPair("buffered", "https://github.com/acme/origin.git", "https://github.com/acme/mirror.git");
        lenient().doAnswer(invocation -> {
            SyncJob job = invocation.getArgument(1);
            noneBus.publish(SyncEventMessage.builder()
                    .jobId(job.getId())
                    .mappingId(job.getMappingId())
                    .pairName(job.getPairName())
                    .ref(job.getRef())
                    .branch(job.getBranch())
                    .build());
            return null;
        }).when(queueProducerService).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        simulation.pauseConsumer();
    }

    @Given("a synthetic push is queued on branch {string}")
    public void syntheticQueuedOnBranch(String branch) {
        emitSynthetic(branch, "abc123");
    }

    @Given("the provider rate limit is simulated")
    public void rateLimitOn() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateRateLimit(true)
                .build());
    }

    @Given("the provider rate limit simulation is turned off")
    public void rateLimitOff() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateRateLimit(false)
                .build());
    }

    @Given("an artificial delay of {int} milliseconds")
    public void artificialDelay(int milliseconds) {
        delayStartedAt = System.nanoTime();
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .artificialDelayMs((long) milliseconds)
                .build());
    }

    @Given("no outage is simulated")
    public void noOutage() {
        simulation.updateSimulationConfig(SimulationConfigRequest.builder()
                .simulateSourceDown(false)
                .simulateTargetDown(false)
                .build());
    }

    @When("a synthetic push is emitted on branch {string} with sha {string}")
    public void emitSynthetic(String branch, String sha) {
        lastSha = sha;
        syntheticJob = simulation.emitSyntheticWebhook(SyntheticWebhookRequest.builder()
                .mappingId("1")
                .branch(branch)
                .commitSha(sha)
                .commitMessage("test commit")
                .authorName("Dev Tester")
                .build());
    }

    @Then("consumers are paused")
    public void consumersPaused() {
        assertTrue(simulation.isConsumerPaused());
    }

    @Then("consumers are running")
    public void consumersRunning() {
        assertFalse(simulation.isConsumerPaused());
    }

    @Then("the sync listeners report running")
    public void listenersRunning() {
        assertTrue(simulation.isListenerRunning());
    }

    @Then("the sync listeners report stopped")
    public void listenersStopped() {
        assertFalse(simulation.isListenerRunning());
    }

    @Then("the full listener is stopped")
    public void fullStopped() {
        verify(fullListener).stop();
    }

    @Then("the incremental listener is stopped")
    public void incrementalStopped() {
        verify(incrementalListener).stop();
    }

    @Then("the full listener is started")
    public void fullStarted() {
        verify(fullListener).start();
    }

    @Then("the incremental listener is started")
    public void incrementalStarted() {
        verify(incrementalListener).start();
    }

    @Then("the sync fails because the destination is unreachable")
    public void destinationUnreachable() {
        assertNotNull(fault);
        assertTrue(fault.getMessage().contains("Destination repository is UNREACHABLE"));
    }

    @Then("the sync fails because the origin is down")
    public void originDownFailure() {
        assertNotNull(fault);
        assertTrue(fault.getMessage().contains("Origin repository is DOWN"));
    }

    @Then("the sync fails because the provider rate limit was exceeded")
    public void rateLimitFailure() {
        assertNotNull(fault);
        assertTrue(fault.getMessage().contains("rate limit"));
    }

    @Then("the fault check returns without error")
    public void faultCheckPasses() {
        assertNull(fault);
    }

    @Then("at least {int} milliseconds elapsed")
    public void elapsed(int milliseconds) {
        long waitedMs = (System.nanoTime() - delayStartedAt) / 1_000_000L;
        assertTrue(waitedMs >= milliseconds, "waited " + waitedMs + "ms");
    }

    @Then("the deferred sync is not dispatched")
    public void deferredNotDispatched() throws Exception {
        assertNotNull(noneBus);
        assertTrue(noneBus.pendingCount() >= 1);
        verify(noneConsumer, never()).consumeSyncEvent(any());
    }

    @Then("the queued job is eligible to run")
    public void queuedJobEligible() throws Exception {
        verify(noneConsumer, timeout(2000)).consumeSyncEvent(any());
        assertEquals(0, noneBus.pendingCount());
    }

    @Then("the synthetic job is queued")
    public void syntheticQueued() {
        assertNotNull(syntheticJob);
        assertEquals("99", syntheticJob.getId());
        assertEquals(SyncStatus.QUEUED, syntheticJob.getStatus());
        assertEquals(TriggerType.SYNTHETIC, syntheticJob.getTriggerType());
        assertEquals(lastSha, syntheticJob.getCommitSha());
    }

    @Then("the sync queue receives that push")
    public void queueReceivesPush() {
        verify(queueProducerService, atLeastOnce()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private SimulationService service(SyncEventBus bus) {
        return new SimulationService(
                listenerRegistryProvider,
                mappingRepository,
                syncJobRepository,
                webSocketNotificationService,
                bus,
                queueProducerService,
                webhookBusControls);
    }
}
