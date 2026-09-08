package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStatusResponse {
    /** Active messaging module wire id: rabbitmq | kafka | none. */
    private String messagingProvider;
    private String messagingDisplayName;
    private String messagingDescription;
    private boolean durableBroker;
    private boolean supportsQueueManager;
    private boolean supportsDlq;
    private boolean supportsPurge;
    private String queueName;
    private int mainQueueMessageCount;
    private int mainQueueUnackedCount;
    private int mainQueueBrokerConsumerCount;
    private String incrementalQueueName;
    private int incrementalQueueMessageCount;
    private int incrementalQueueUnackedCount;
    private int incrementalQueueBrokerConsumerCount;
    private String inboundQueueName;
    private int inboundQueueMessageCount;
    private int inboundQueueUnackedCount;
    private int inboundQueueBrokerConsumerCount;
    private String dlqQueueName;
    private int dlqMessageCount;
    private int dlqBrokerConsumerCount;
    private boolean consumerRunning;
    private boolean fullConsumerRunning;
    private boolean incrementalConsumerRunning;
    private boolean inboundConsumerRunning;
    private boolean consumerPaused;
    private boolean brokerConnected;
    private String brokerAddress;
    private SimulationStatus simulationStatus;
    private Instant timestamp;
    @Builder.Default
    private List<ConsumerLaneStatus> consumers = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimulationStatus {
        private boolean consumerPaused;
        private boolean simulateTargetDown;
        private boolean simulateSourceDown;
        private boolean simulateRateLimit;
        private long artificialDelayMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumerLaneStatus {
        private String lane;
        private String label;
        private String listenerId;
        private String queueName;
        private int readyCount;
        private int unackedCount;
        private int brokerConsumerCount;
        private boolean running;
        private boolean paused;
        private boolean dead;
        private int configuredConcurrency;
        private int maxConcurrency;
        private int activeConsumers;
        private int idleThreads;
        private int unusedSlots;
        @Builder.Default
        private List<CurrentWork> currentWork = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentWork {
        private Long jobId;
        private String pairName;
        private String ref;
        private String threadName;
        private boolean threadAlive;
        private String state;
        private long elapsedMs;
    }
}
