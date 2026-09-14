package com.gitutility.service;

import com.gitutility.messaging.MessagingDescriptor;
import com.gitutility.messaging.MessagingModule;
import com.gitutility.messaging.MessagingProvider;
import com.gitutility.messaging.none.NoneSyncEventBus;
import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueObservabilityService {

    private final ConsumerRuntimeRegistry consumerRuntimeRegistry;
    private final SimulationService simulationService;
    private final ObjectProvider<DlqRedriveService> dlqRedriveService;
    private final SyncEventBus syncEventBus;
    private final MessagingModule messagingModule;

    @Value("${git-utility.queue.main-queue:git.sync.queue}")
    private String mainQueueName;

    @Value("${git-utility.queue.incremental-queue:git.sync.incremental.queue}")
    private String incrementalQueueName;

    @Value("${git-utility.queue.inbound-queue:git.sync.inbound.queue}")
    private String inboundQueueName;

    public List<QueueStatusResponse.ConsumerLaneStatus> snapshotLanes() {
        MessagingDescriptor messaging = messagingModule.descriptor();
        boolean noneMode = messaging.getProvider() == MessagingProvider.NONE;
        boolean executionPaused = simulationService.isConsumerPaused();
        List<QueueStatusResponse.ConsumerLaneStatus> lanes = new ArrayList<>();
        lanes.add(describe(
                SyncLaneRouter.LANE_FULL,
                noneMode ? "Full mirrors (in-process)" : "Full mirrors",
                SyncLaneRouter.FULL_CONSUMER_ID,
                noneMode ? noneQueueLabel(messaging) : mainQueueName,
                executionPaused,
                messaging
        ));
        lanes.add(describe(
                SyncLaneRouter.LANE_INCREMENTAL,
                noneMode ? "Branch sync (in-process)" : "Webhook syncs",
                SyncLaneRouter.INCREMENTAL_CONSUMER_ID,
                noneMode ? noneQueueLabel(messaging) : incrementalQueueName,
                executionPaused,
                messaging
        ));
        if (messaging.isSupportsInboundBrokerQueue()) {
            lanes.add(describe(
                    SyncLaneRouter.LANE_INBOUND,
                    "Inbound webhooks",
                    SyncLaneRouter.INBOUND_CONSUMER_ID,
                    inboundQueueName,
                    false,
                    messaging
            ));
        }
        return lanes;
    }

    private static String noneQueueLabel(MessagingDescriptor messaging) {
        int n = messaging.getWorkerThreads() != null ? messaging.getWorkerThreads() : 8;
        return "none-messaging-worker × " + n;
    }

    private QueueStatusResponse.ConsumerLaneStatus describe(
            String lane,
            String label,
            String listenerId,
            String queueName,
            boolean paused,
            MessagingDescriptor messaging
    ) {
        boolean noneMode = messaging.getProvider() == MessagingProvider.NONE;
        DlqRedriveService.QueueDepth depth = depthFor(queueName, lane, messaging);
        SimulationService.ListenerHealth health = simulationService.getListenerHealth(listenerId);
        List<ConsumerRuntimeRegistry.Slot> slots = consumerRuntimeRegistry.slotsForLane(lane);
        int unacked = slots.size();
        if (noneMode && SyncLaneRouter.LANE_FULL.equals(lane) && syncEventBus instanceof NoneSyncEventBus bus) {
            // Shared pool: surface total in-flight on FULL; INCREMENTAL still shows registry slots.
            unacked = Math.max(unacked, bus.inFlightCount());
        }
        int configured;
        int active;
        int maxConcurrency;
        boolean running;
        if (noneMode) {
            configured = messaging.getWorkerThreads() != null ? messaging.getWorkerThreads() : 8;
            if (syncEventBus instanceof NoneSyncEventBus bus) {
                configured = bus.workerThreads();
            }
            active = paused ? 0 : Math.min(configured, Math.max(unacked, syncEventBus instanceof NoneSyncEventBus bus
                    ? bus.inFlightCount() : unacked));
            if (!paused && SyncLaneRouter.LANE_INCREMENTAL.equals(lane)) {
                active = Math.min(configured, unacked);
            }
            maxConcurrency = configured;
            running = !paused;
        } else {
            configured = health.concurrentConsumers();
            active = health.activeConsumers();
            maxConcurrency = health.maxConcurrentConsumers();
            running = health.running();
        }
        int idle = Math.max(0, active - unacked);
        int unused = running ? Math.max(0, configured - active) : 0;
        boolean dead = !paused && health.present() && !running && !noneMode;

        List<QueueStatusResponse.CurrentWork> work = new ArrayList<>();
        for (ConsumerRuntimeRegistry.Slot slot : slots) {
            work.add(QueueStatusResponse.CurrentWork.builder()
                    .jobId(slot.getJobId())
                    .pairName(slot.getPairName())
                    .ref(slot.getRef())
                    .threadName(slot.getThreadName())
                    .threadAlive(slot.isThreadAlive())
                    .state(slot.getState().name())
                    .elapsedMs(slot.elapsedMs())
                    .build());
        }

        return QueueStatusResponse.ConsumerLaneStatus.builder()
                .lane(lane)
                .label(label)
                .listenerId(listenerId)
                .queueName(queueName)
                .readyCount(depth.ready())
                .unackedCount(unacked)
                .brokerConsumerCount(depth.consumerCount())
                .running(running)
                .paused(paused)
                .dead(dead)
                .configuredConcurrency(configured)
                .maxConcurrency(maxConcurrency)
                .activeConsumers(active)
                .idleThreads(idle)
                .unusedSlots(unused)
                .currentWork(work)
                .build();
    }

    private DlqRedriveService.QueueDepth depthFor(String queueName, String lane, MessagingDescriptor messaging) {
        DlqRedriveService dlq = dlqRedriveService.getIfAvailable();
        if (dlq != null) {
            return dlq.getQueueDepth(queueName);
        }
        if (messaging.getProvider() == MessagingProvider.NONE
                && syncEventBus instanceof NoneSyncEventBus bus
                && (SyncLaneRouter.LANE_FULL.equals(lane) || SyncLaneRouter.LANE_INCREMENTAL.equals(lane))) {
            // Pending deferred work is not lane-split; show it on the full lane only.
            int pending = SyncLaneRouter.LANE_FULL.equals(lane) ? bus.pendingCount() : 0;
            int consumers = simulationService.isConsumerPaused() ? 0 : bus.workerThreads();
            return new DlqRedriveService.QueueDepth(pending, consumers);
        }
        return DlqRedriveService.QueueDepth.EMPTY;
    }
}
