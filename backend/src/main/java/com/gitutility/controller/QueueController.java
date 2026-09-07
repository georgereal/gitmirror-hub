package com.gitutility.controller;

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

    private final DlqRedriveService dlqRedriveService;
    private final SimulationService simulationService;
    private final ConnectionFactory connectionFactory;
    private final SyncJobService syncJobService;
    private final QueueObservabilityService queueObservabilityService;

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
        boolean brokerConnected = false;
        try (Connection conn = connectionFactory.createConnection()) {
            brokerConnected = conn.isOpen();
        } catch (Exception e) {
            log.debug("Broker connection check: {}", e.getMessage());
        }

        int mainQueueCount = dlqRedriveService.getMainQueueCount();
        int incrementalQueueCount = dlqRedriveService.getIncrementalQueueCount();
        int inboundQueueCount = dlqRedriveService.getInboundQueueCount();
        int dlqCount = dlqRedriveService.getDlqCount();
        var dlqDepth = dlqRedriveService.getQueueDepth(dlqQueueName);
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
                .brokerConnected(brokerConnected)
                .brokerAddress(maskBrokerAddress(brokerAddress))
                .simulationStatus(simStatus)
                .timestamp(Instant.now())
                .consumers(lanes)
                .build();

        return ResponseEntity.ok(response);
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
        int count = dlqRedriveService.redriveAllDlqMessages();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "messagesRedriven", count,
                "message", count + " messages replayed from DLQ onto the matching execution lane"
        ));
    }

    @PostMapping("/dlq/purge")
    public ResponseEntity<Map<String, Object>> purgeDlq() {
        boolean success = dlqRedriveService.purgeDlq();
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
        int cancelled = syncJobService.cancelQueuedJobs(null);
        boolean purged = dlqRedriveService.purgeMainQueue();
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
        boolean success = dlqRedriveService.purgeInboundQueue();
        return ResponseEntity.ok(Map.of(
                "status", success ? "success" : "failed",
                "message", success ? "Inbound webhook queue purged" : "Failed to purge inbound queue"
        ));
    }

    static String maskBrokerAddress(String address) {
        if (address == null || address.isBlank()) {
            return address;
        }
        return address.replaceAll("://([^:/]+):([^@]+)@", "://$1:••••@");
    }
}
