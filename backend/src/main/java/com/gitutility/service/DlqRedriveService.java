package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import com.gitutility.messaging.MessagingConditions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Properties;

@Service
@MessagingConditions.OnRabbitMq
@RequiredArgsConstructor
@Slf4j
public class DlqRedriveService {

    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    private final SyncJobRepository syncJobRepository;
    private final WebSocketNotificationService webSocketNotificationService;

    @Value("${git-utility.queue.exchange:git.sync.exchange}")
    private String mainExchangeName;

    @Value("${git-utility.queue.main-queue:git.sync.queue}")
    private String mainQueueName;

    @Value("${git-utility.queue.routing-key:git.sync.key}")
    private String mainRoutingKey;

    @Value("${git-utility.queue.dlq-queue:git.sync.dlq}")
    private String dlqQueueName;

    @Value("${git-utility.queue.inbound-queue:git.sync.inbound.queue}")
    private String inboundQueueName;

    @Value("${git-utility.queue.incremental-queue:git.sync.incremental.queue}")
    private String incrementalQueueName;

    @Value("${git-utility.queue.incremental-routing-key:git.sync.incremental.key}")
    private String incrementalRoutingKey;

    public int getQueueCount(String queueName) {
        return getQueueDepth(queueName).ready();
    }

    public QueueDepth getQueueDepth(String queueName) {
        try {
            RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
            Properties props = admin.getQueueProperties(queueName);
            if (props != null) {
                int ready = intProp(props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
                int consumers = intProp(props.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
                return new QueueDepth(ready, consumers);
            }
        } catch (Exception e) {
            log.warn("Could not query queue depth for '{}': {}", queueName, e.getMessage());
        }
        return QueueDepth.EMPTY;
    }

    private static int intProp(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public record QueueDepth(int ready, int consumerCount) {
        public static final QueueDepth EMPTY = new QueueDepth(0, 0);
    }

    public int getMainQueueCount() {
        return getQueueCount(mainQueueName);
    }

    public int getDlqCount() {
        return getQueueCount(dlqQueueName);
    }

    public int getInboundQueueCount() {
        return getQueueCount(inboundQueueName);
    }

    public int getIncrementalQueueCount() {
        return getQueueCount(incrementalQueueName);
    }

    /**
     * Replays all messages currently in the Dead Letter Queue back into the main sync queue.
     */
    public int redriveAllDlqMessages() {
        int count = 0;
        log.info("Starting DLQ redrive operation from queue: {}", dlqQueueName);

        while (true) {
            Message rawMessage = rabbitTemplate.receive(dlqQueueName, 2000);
            if (rawMessage == null) {
                break;
            }

            try {
                Object converted = messageConverter.fromMessage(rawMessage);
                if (converted instanceof SyncEventMessage event) {
                    event.setTriggerType(TriggerType.DLQ_REDRIVE);
                    event.setEnqueuedAt(Instant.now());

                    SyncJob job = syncJobRepository.findById(event.getJobId()).orElse(null);
                    if (job != null) {
                        job.setStatus(SyncStatus.QUEUED);
                        job.setTriggerType(TriggerType.DLQ_REDRIVE);
                        job.setErrorMessage("Redriven from DLQ");
                        syncJobRepository.save(job);
                        webSocketNotificationService.notifyJobUpdated(job);
                    }

                    String routingKey = SyncLaneRouter.routingKey(
                            event.getRef(), event.getBranch(), mainRoutingKey, incrementalRoutingKey);
                    rabbitTemplate.convertAndSend(mainExchangeName, routingKey, event);
                    count++;
                    log.info("Redriven message {} (Job #{}) to routing key {}",
                            event.getMessageId(), event.getJobId(), routingKey);
                } else {
                    log.warn("Received non-SyncEventMessage from DLQ: {}", converted);
                }
            } catch (Exception e) {
                log.error("Failed to redrive message from DLQ: {}", e.getMessage());
            }
        }

        log.info("Completed DLQ redrive. Total messages replayed: {}", count);
        return count;
    }

    /**
     * Purges waiting messages from both execution lanes. Unacked in-flight work is not dropped.
     */
    public boolean purgeMainQueue() {
        boolean full = purgeNamedQueue(mainQueueName);
        boolean incremental = purgeNamedQueue(incrementalQueueName);
        return full && incremental;
    }

    public boolean purgeIncrementalQueue() {
        return purgeNamedQueue(incrementalQueueName);
    }

    public boolean purgeInboundQueue() {
        return purgeNamedQueue(inboundQueueName);
    }

    public boolean purgeDlq() {
        return purgeNamedQueue(dlqQueueName);
    }

    private boolean purgeNamedQueue(String queueName) {
        try {
            RabbitAdmin admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
            admin.purgeQueue(queueName, false);
            log.info("Purged queue {}", queueName);
            return true;
        } catch (Exception e) {
            log.error("Failed to purge queue {}: {}", queueName, e.getMessage());
            return false;
        }
    }
}
