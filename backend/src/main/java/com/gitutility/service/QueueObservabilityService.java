package com.gitutility.service;

import com.gitutility.model.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueObservabilityService {

    private final ConsumerRuntimeRegistry consumerRuntimeRegistry;
    private final SimulationService simulationService;
    private final DlqRedriveService dlqRedriveService;

    @Value("${git-utility.queue.main-queue:git.sync.queue}")
    private String mainQueueName;

    @Value("${git-utility.queue.incremental-queue:git.sync.incremental.queue}")
    private String incrementalQueueName;

    @Value("${git-utility.queue.inbound-queue:git.sync.inbound.queue}")
    private String inboundQueueName;

    public List<QueueStatusResponse.ConsumerLaneStatus> snapshotLanes() {
        boolean executionPaused = simulationService.isConsumerPaused();
        List<QueueStatusResponse.ConsumerLaneStatus> lanes = new ArrayList<>();
        lanes.add(describe(
                SyncLaneRouter.LANE_FULL,
                "Full mirrors",
                SyncLaneRouter.FULL_CONSUMER_ID,
                mainQueueName,
                executionPaused
        ));
        lanes.add(describe(
                SyncLaneRouter.LANE_INCREMENTAL,
                "Webhook syncs",
                SyncLaneRouter.INCREMENTAL_CONSUMER_ID,
                incrementalQueueName,
                executionPaused
        ));
        lanes.add(describe(
                SyncLaneRouter.LANE_INBOUND,
                "Inbound webhooks",
                SyncLaneRouter.INBOUND_CONSUMER_ID,
                inboundQueueName,
                false
        ));
        return lanes;
    }

    private QueueStatusResponse.ConsumerLaneStatus describe(
            String lane,
            String label,
            String listenerId,
            String queueName,
            boolean paused
    ) {
        DlqRedriveService.QueueDepth depth = dlqRedriveService.getQueueDepth(queueName);
        SimulationService.ListenerHealth health = simulationService.getListenerHealth(listenerId);
        List<ConsumerRuntimeRegistry.Slot> slots = consumerRuntimeRegistry.slotsForLane(lane);
        int unacked = slots.size();
        int active = health.activeConsumers();
        int configured = health.concurrentConsumers();
        int idle = Math.max(0, active - unacked);
        int unused = health.running() ? Math.max(0, configured - active) : 0;
        boolean dead = !paused && !health.running();

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
                .running(health.running())
                .paused(paused)
                .dead(dead)
                .configuredConcurrency(configured)
                .maxConcurrency(health.maxConcurrentConsumers())
                .activeConsumers(active)
                .idleThreads(idle)
                .unusedSlots(unused)
                .currentWork(work)
                .build();
    }
}
