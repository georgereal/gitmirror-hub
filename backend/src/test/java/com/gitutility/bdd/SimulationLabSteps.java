package com.gitutility.bdd;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.messaging.none.NoneSyncEventBus;
import com.gitutility.messaging.webhook.WebhookBusControls;
import com.gitutility.model.dto.SimulationConfigRequest;
import com.gitutility.model.dto.SyntheticWebhookRequest;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.SimulationService;
import com.gitutility.service.SyncLaneRouter;
import com.gitutility.service.WebSocketNotificationService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        simulation = service(mock(SyncEventBus.class));
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

    @When("a synthetic push is emitted on branch {string} with sha {string}")
    public void emitSynthetic(String branch, String sha) {
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

    @Then("the synthetic job is queued")
    public void syntheticQueued() {
        assertNotNull(syntheticJob);
        assertEquals("99", syntheticJob.getId());
        assertEquals(SyncStatus.QUEUED, syntheticJob.getStatus());
        assertEquals(TriggerType.SYNTHETIC, syntheticJob.getTriggerType());
        assertEquals("abcdef123", syntheticJob.getCommitSha());
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
