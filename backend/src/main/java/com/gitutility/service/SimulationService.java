package com.gitutility.service;

import com.gitutility.model.dto.SimulationConfigRequest;
import com.gitutility.model.dto.SyntheticWebhookRequest;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final RepoMappingRepository mappingRepository;
    private final SyncJobRepository syncJobRepository;
    private final WebSocketNotificationService webSocketNotificationService;

    @Lazy
    private final QueueProducerService queueProducerService;

    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);
    /** When true, this pod paused itself on boot; cluster poller must not auto-resume. */
    private final AtomicBoolean startupHold = new AtomicBoolean(false);
    private final AtomicBoolean simulateTargetDown = new AtomicBoolean(false);
    private final AtomicBoolean simulateSourceDown = new AtomicBoolean(false);
    private final AtomicBoolean simulateRateLimit = new AtomicBoolean(false);
    private final AtomicLong artificialDelayMs = new AtomicLong(0);

    @Value("${spring.rabbitmq.listener.simple.concurrency:1}")
    private int defaultConcurrency = 1;

    @Value("${spring.rabbitmq.listener.simple.max-concurrency:5}")
    private int defaultMaxConcurrency = 5;

    public boolean isConsumerPaused() {
        return consumerPaused.get();
    }

    public boolean isStartupHold() {
        return startupHold.get();
    }

    public void markStartupHold() {
        startupHold.set(true);
    }

    public void clearStartupHold() {
        startupHold.set(false);
    }

    public boolean isListenerRunning() {
        boolean found = false;
        for (String listenerId : SyncLaneRouter.EXECUTION_LISTENER_IDS) {
            MessageListenerContainer container = listenerContainer(listenerId);
            if (container == null) {
                continue;
            }
            found = true;
            if (!container.isRunning()) {
                return false;
            }
        }
        return found;
    }

    public boolean isFullListenerRunning() {
        return isNamedListenerRunning(SyncLaneRouter.FULL_CONSUMER_ID)
                || isNamedListenerRunning(SyncLaneRouter.LEGACY_CONSUMER_ID);
    }

    public boolean isIncrementalListenerRunning() {
        return isNamedListenerRunning(SyncLaneRouter.INCREMENTAL_CONSUMER_ID);
    }

    public boolean isInboundListenerRunning() {
        return isNamedListenerRunning(SyncLaneRouter.INBOUND_CONSUMER_ID);
    }

    public ListenerHealth getListenerHealth(String listenerId) {
        MessageListenerContainer container = listenerContainer(listenerId);
        if (container == null && SyncLaneRouter.FULL_CONSUMER_ID.equals(listenerId)) {
            container = listenerContainer(SyncLaneRouter.LEGACY_CONSUMER_ID);
        }
        if (container == null) {
            return new ListenerHealth(false, false, 0, defaultConcurrency, defaultMaxConcurrency);
        }
        int active = container.isRunning() ? 1 : 0;
        if (container instanceof SimpleMessageListenerContainer simple) {
            active = simple.getActiveConsumerCount();
        }
        return new ListenerHealth(true, container.isRunning(), active, defaultConcurrency, defaultMaxConcurrency);
    }

    public record ListenerHealth(
            boolean present,
            boolean running,
            int activeConsumers,
            int concurrentConsumers,
            int maxConcurrentConsumers
    ) {
    }

    private boolean isNamedListenerRunning(String listenerId) {
        MessageListenerContainer container = listenerContainer(listenerId);
        return container != null && container.isRunning();
    }

    private MessageListenerContainer listenerContainer(String listenerId) {
        try {
            return listenerRegistry.getListenerContainer(listenerId);
        } catch (Exception e) {
            log.debug("Listener container '{}' state check: {}", listenerId, e.getMessage());
            return null;
        }
    }

    /**
     * Pause the RabbitMQ queue consumer listener.
     * New webhook messages will accumulate in the queue without being processed.
     */
    public boolean pauseConsumer() {
        return pauseConsumerLocal();
    }

    /**
     * Resume the RabbitMQ queue consumer listener to drain the queued message backlog.
     */
    public boolean resumeConsumer() {
        return resumeConsumerLocal();
    }

    /** Local-only pause used by cluster control poller (avoids feedback loops). */
    public boolean pauseConsumerLocal() {
        consumerPaused.set(true);
        forEachExecutionListener(container -> {
            if (container.isRunning()) {
                container.stop();
            }
        });
        log.info("Paused Git Sync RabbitMQ execution listeners");
        broadcastSimulationState();
        return true;
    }

    /** Local-only resume used by cluster control poller (avoids feedback loops). */
    public boolean resumeConsumerLocal() {
        startupHold.set(false);
        consumerPaused.set(false);
        forEachExecutionListener(container -> {
            if (!container.isRunning()) {
                container.start();
            }
        });
        log.info("Resumed Git Sync RabbitMQ execution listeners");
        broadcastSimulationState();
        return true;
    }

    private void forEachExecutionListener(java.util.function.Consumer<MessageListenerContainer> action) {
        for (String listenerId : SyncLaneRouter.EXECUTION_LISTENER_IDS) {
            MessageListenerContainer container = listenerContainer(listenerId);
            if (container == null) {
                continue;
            }
            try {
                action.accept(container);
            } catch (Exception e) {
                log.warn("Exception updating listener container '{}': {}", listenerId, e.getMessage());
            }
        }
    }

    public boolean isSimulateTargetDown() {
        return simulateTargetDown.get();
    }

    public boolean isSimulateSourceDown() {
        return simulateSourceDown.get();
    }

    public boolean isSimulateRateLimit() {
        return simulateRateLimit.get();
    }

    public long getArtificialDelayMs() {
        return artificialDelayMs.get();
    }

    public void updateSimulationConfig(SimulationConfigRequest config) {
        if (config.getConsumerPaused() != null) {
            if (config.getConsumerPaused()) {
                pauseConsumer();
            } else {
                resumeConsumer();
            }
        }
        if (config.getSimulateTargetDown() != null) {
            simulateTargetDown.set(config.getSimulateTargetDown());
        }
        if (config.getSimulateSourceDown() != null) {
            simulateSourceDown.set(config.getSimulateSourceDown());
        }
        if (config.getSimulateRateLimit() != null) {
            simulateRateLimit.set(config.getSimulateRateLimit());
        }
        if (config.getArtificialDelayMs() != null) {
            artificialDelayMs.set(Math.max(0, config.getArtificialDelayMs()));
        }
        broadcastSimulationState();
    }

    public Map<String, Object> getSimulationState() {
        Map<String, Object> state = new HashMap<>();
        state.put("consumerPaused", consumerPaused.get());
        state.put("simulateTargetDown", simulateTargetDown.get());
        state.put("simulateSourceDown", simulateSourceDown.get());
        state.put("simulateRateLimit", simulateRateLimit.get());
        state.put("artificialDelayMs", artificialDelayMs.get());
        return state;
    }

    public void checkInjectedFaults() throws Exception {
        if (artificialDelayMs.get() > 0) {
            try {
                Thread.sleep(artificialDelayMs.get());
            } catch (InterruptedException ignored) {}
        }

        if (simulateSourceDown.get()) {
            throw new RuntimeException("Simulated Fault: Origin repository is DOWN (HTTP 503 / Connection Timeout)");
        }
        if (simulateTargetDown.get()) {
            throw new RuntimeException("Simulated Fault: Destination repository is UNREACHABLE (HTTP 500 Connection Refused)");
        }
        if (simulateRateLimit.get()) {
            throw new RuntimeException("Simulated Fault: GitHub API secondary rate limit exceeded (HTTP 429 Retry-After)");
        }
    }

    /**
     * Generates a synthetic GitHub webhook push event for testing queue and mirroring flows.
     */
    public SyncJob emitSyntheticWebhook(SyntheticWebhookRequest request) {
        RepoMapping mapping = mappingRepository.findById(request.getMappingId())
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found with id: " + request.getMappingId()));

        String sourceRepo = request.getSourceRepo() != null ? request.getSourceRepo() : mapping.getRepoAUrl();
        String targetRepo = sourceRepo.equals(mapping.getRepoAUrl()) ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        String branch = request.getBranch() != null ? request.getBranch() : "main";
        String ref = "refs/heads/" + branch;
        String commitSha = (request.getCommitSha() != null && !request.getCommitSha().isBlank())
                ? request.getCommitSha()
                : UUID.randomUUID().toString().substring(0, 7);
        String commitMsg = (request.getCommitMessage() != null && !request.getCommitMessage().isBlank())
                ? request.getCommitMessage()
                : "chore: synthetic test commit for queue validation";
        String author = request.getAuthorName() != null ? request.getAuthorName() : "Test User <test@example.com>";

        SyncJob job = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(sourceRepo)
                .targetRepo(targetRepo)
                .ref(ref)
                .branch(branch)
                .commitSha(commitSha)
                .commitMessage(commitMsg)
                .author(author)
                .status(SyncStatus.QUEUED)
                .triggerType(TriggerType.SYNTHETIC)
                .createdAt(Instant.now())
                .build();

        job = syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);

        queueProducerService.enqueueSyncJob(mapping, job, sourceRepo, targetRepo, ref, branch, null, commitSha, commitMsg, author, TriggerType.SYNTHETIC);
        return job;
    }

    private void broadcastSimulationState() {
        webSocketNotificationService.notifySimulationUpdated(getSimulationState());
    }
}
