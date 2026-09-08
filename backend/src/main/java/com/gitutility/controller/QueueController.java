package com.gitutility.controller;

import com.gitutility.messaging.MessagingDescriptor;
import com.gitutility.messaging.MessagingModule;
import com.gitutility.messaging.none.NoneSyncEventBus;
import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.QueueStatusResponse;
import com.gitutility.service.DlqRedriveService;
import com.gitutility.service.QueueObservabilityService;
import com.gitutility.service.SimulationService;
import com.gitutility.service.SyncJobService;
import com.gitutility.service.SyncLaneRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
@Slf4j
public class QueueController {

    private final ObjectProvider<DlqRedriveService> dlqRedriveService;
    private final SimulationService simulationService;
    private final ObjectProvider<ConnectionFactory> connectionFactory;
    private final SyncJobService syncJobService;
    private final QueueObservabilityService queueObservabilityService;
    private final MessagingModule messagingModule;
    private final SyncEventBus syncEventBus;

    @Value("${git-utility.queue.main-queue:git.sync.queue}")
    private String mainQueueName;

    @Value("${git-utility.queue.dlq-queue:git.sync.dlq}")
    private String dlqQueueName;

    @Value("${git-utility.queue.inbound-queue:git.sync.inbound.queue}")
    private String inboundQueueName;

    @Value("${git-utility.queue.incremental-queue:git.sync.incremental.queue}")
    private String incrementalQueueName;

    @Value("${spring.rabbitmq.addresses:amqp://localhost:5672}")
    private String brokerAddress;

    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getQueueStatus() {
        MessagingDescriptor messaging = messagingModule.descriptor();
        boolean brokerConnected = false;
        ConnectionFactory cf = connectionFactory.getIfAvailable();
        if (cf != null) {
            try (Connection conn = cf.createConnection()) {
                brokerConnected = conn.isOpen();
            } catch (Exception e) {
                log.debug("Broker connection check: {}", e.getMessage());
            }
        }

        DlqRedriveService dlq = dlqRedriveService.getIfAvailable();
        int mainQueueCount = dlq != null ? dlq.getMainQueueCount() : nonePending();
        int incrementalQueueCount = dlq != null ? dlq.getIncrementalQueueCount() : 0;
        int inboundQueueCount = dlq != null ? dlq.getInboundQueueCount() : 0;
        int dlqCount = dlq != null ? dlq.getDlqCount() : 0;
        var dlqDepth = dlq != null ? dlq.getQueueDepth(dlqQueueName) : DlqRedriveService.QueueDepth.EMPTY;
        boolean consumerPaused = simulationService.isConsumerPaused();
        boolean fullRunning = simulationService.isFullListenerRunning();
        boolean incrementalRunning = simulationService.isIncrementalListenerRunning();
        boolean inboundRunning = simulationService.isInboundListenerRunning();
        boolean listenerRunning = simulationService.isListenerRunning();
        var lanes = queueObservabilityService.snapshotLanes();
        QueueStatusResponse.ConsumerLaneStatus fullLane = lane(lanes, SyncLaneRouter.LANE_FULL);
        QueueStatusResponse.ConsumerLaneStatus incrementalLane = lane(lanes, SyncLaneRouter.LANE_INCREMENTAL);
        QueueStatusResponse.ConsumerLaneStatus inboundLane = lane(lanes, SyncLaneRouter.LANE_INBOUND);

        QueueStatusResponse.SimulationStatus simStatus = QueueStatusResponse.SimulationStatus.builder()
                .consumerPaused(consumerPaused)
                .simulateTargetDown(simulationService.isSimulateTargetDown())
                .simulateSourceDown(simulationService.isSimulateSourceDown())
                .simulateRateLimit(simulationService.isSimulateRateLimit())
                .artificialDelayMs(simulationService.getArtificialDelayMs())
                .build();

        QueueStatusResponse response = QueueStatusResponse.builder()
                .messagingProvider(messaging.getProvider().wireId())
                .messagingDisplayName(messaging.getDisplayName())
                .messagingDescription(messaging.getDescription())
                .durableBroker(messaging.isDurableBroker())
                .supportsQueueManager(messaging.isSupportsQueueManager())
                .supportsDlq(messaging.isSupportsDlq())
                .supportsPurge(messaging.isSupportsPurge())
                .queueName(mainQueueName)
                .mainQueueMessageCount(mainQueueCount)
                .mainQueueUnackedCount(fullLane != null ? fullLane.getUnackedCount() : 0)
                .mainQueueBrokerConsumerCount(fullLane != null ? fullLane.getBrokerConsumerCount() : 0)
                .incrementalQueueName(incrementalQueueName)
                .incrementalQueueMessageCount(incrementalQueueCount)
                .incrementalQueueUnackedCount(incrementalLane != null ? incrementalLane.getUnackedCount() : 0)
                .incrementalQueueBrokerConsumerCount(incrementalLane != null ? incrementalLane.getBrokerConsumerCount() : 0)
                .inboundQueueName(inboundQueueName)
                .inboundQueueMessageCount(inboundQueueCount)
                .inboundQueueUnackedCount(inboundLane != null ? inboundLane.getUnackedCount() : 0)
                .inboundQueueBrokerConsumerCount(inboundLane != null ? inboundLane.getBrokerConsumerCount() : 0)
                .dlqQueueName(dlqQueueName)
                .dlqMessageCount(dlqCount)
                .dlqBrokerConsumerCount(dlqDepth.consumerCount())
                .consumerRunning(listenerRunning)
                .fullConsumerRunning(fullRunning)
                .incrementalConsumerRunning(incrementalRunning)
                .inboundConsumerRunning(inboundRunning)
                .consumerPaused(consumerPaused)
                .brokerConnected(brokerConnected || (!messaging.isDurableBroker() && !consumerPaused))
                .brokerAddress(messaging.isDurableBroker() ? maskBrokerAddress(brokerAddress) : "none://local-jvm")
                .simulationStatus(simStatus)
                .timestamp(Instant.now())
                .consumers(lanes)
                .build();

        return ResponseEntity.ok(response);
    }

    private int nonePending() {
        return syncEventBus instanceof NoneSyncEventBus bus ? bus.pendingCount() : 0;
    }

    private static QueueStatusResponse.ConsumerLaneStatus lane(
            java.util.List<QueueStatusResponse.ConsumerLaneStatus> lanes, String id) {
        if (lanes == null) {
            return null;
        }
        return lanes.stream().filter(l -> id.equals(l.getLane())).findFirst().orElse(null);
    }

    @PostMapping("/dlq/redrive")
    public ResponseEntity<Map<String, Object>> redriveDlq() {
        DlqRedriveService dlq = requireBrokerOps();
        if (dlq == null) {
            return unsupported("DLQ redrive requires a durable broker messaging module");
        }
        int count = dlq.redriveAllDlqMessages();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "messagesRedriven", count,
                "message", count + " messages replayed from DLQ onto the matching execution lane"
        ));
    }

    @PostMapping("/dlq/purge")
    public ResponseEntity<Map<String, Object>> purgeDlq() {
        DlqRedriveService dlq = requireBrokerOps();
        if (dlq == null) {
            return unsupported("DLQ purge requires a durable broker messaging module");
        }
        boolean success = dlq.purgeDlq();
        return ResponseEntity.ok(Map.of(
                "status", success ? "success" : "failed",
                "message", success ? "DLQ purged successfully" : "Failed to purge DLQ"
        ));
    }

    /**
     * Drops waiting execution-lane messages (full + incremental) and marks remaining QUEUED
     * jobs cancelled so they cannot be resurrected as orphans. The in-flight unacked job is not dropped.
     */
    @PostMapping("/purge")
    public ResponseEntity<Map<String, Object>> purgeMainQueue() {
        DlqRedriveService dlq = requireBrokerOps();
        if (dlq == null) {
            int cancelled = syncJobService.cancelQueuedJobs(null);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "cancelledCount", cancelled,
                    "message", "Inline mode: cancelled " + cancelled
                            + " queued job(s). Deferred in-memory work is not purged — resume or restart."
            ));
        }
        int cancelled = syncJobService.cancelQueuedJobs(null);
        boolean purged = dlq.purgeMainQueue();
        return ResponseEntity.ok(Map.of(
                "status", purged ? "success" : "failed",
                "cancelledCount", cancelled,
                "message", purged
                        ? "Full and incremental queues purged; " + cancelled + " queued job(s) cancelled"
                        : "Failed to purge execution queues"
        ));
    }

    @PostMapping("/inbound/purge")
    public ResponseEntity<Map<String, Object>> purgeInboundQueue() {
        DlqRedriveService dlq = requireBrokerOps();
        if (dlq == null) {
            return unsupported("Inbound purge requires a durable broker messaging module");
        }
        boolean success = dlq.purgeInboundQueue();
        return ResponseEntity.ok(Map.of(
                "status", success ? "success" : "failed",
                "message", success ? "Inbound webhook queue purged" : "Failed to purge inbound queue"
        ));
    }

    private DlqRedriveService requireBrokerOps() {
        if (!messagingModule.descriptor().isSupportsPurge() && !messagingModule.descriptor().isSupportsDlq()) {
            return null;
        }
        return dlqRedriveService.getIfAvailable();
    }

    private static ResponseEntity<Map<String, Object>> unsupported(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "unsupported",
                "message", message
        ));
    }

    static String maskBrokerAddress(String address) {
        if (address == null || address.isBlank()) {
            return address;
        }
        return address.replaceAll("://([^:/]+):([^@]+)@", "://$1:••••@");
    }
}
