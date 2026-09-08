package com.gitutility.messaging.rabbit;

import com.gitutility.messaging.MessagingConditions;
import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.service.SyncLaneRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@MessagingConditions.OnRabbitMq
@RequiredArgsConstructor
@Slf4j
public class RabbitSyncEventBus implements SyncEventBus {

    private final RabbitTemplate rabbitTemplate;

    @Value("${git-utility.queue.exchange:git.sync.exchange}")
    private String exchangeName;

    @Value("${git-utility.queue.routing-key:git.sync.key}")
    private String fullRoutingKey;

    @Value("${git-utility.queue.incremental-routing-key:git.sync.incremental.key}")
    private String incrementalRoutingKey;

    @Override
    public void publish(SyncEventMessage message) {
        send(message, false);
    }

    @Override
    public void republish(SyncEventMessage message) {
        send(message, true);
    }

    private void send(SyncEventMessage message, boolean republish) {
        if (message == null || message.getJobId() == null) {
            return;
        }
        String routingKey = SyncLaneRouter.routingKey(
                message.getRef(), message.getBranch(), fullRoutingKey, incrementalRoutingKey);
        String messageId = message.getMessageId() != null ? message.getMessageId() : UUID.randomUUID().toString();
        message.setMessageId(messageId);
        if (republish) {
            message.setEnqueuedAt(Instant.now());
            log.info("Re-publishing sync event for Job #{} onto lane {} (lease/contention defer)",
                    message.getJobId(), SyncLaneRouter.lane(message.getRef(), message.getBranch()));
        } else {
            log.info("Publishing sync event to exchange '{}' with routing key '{}' (lane {}) for Job #{} [{}]",
                    exchangeName, routingKey, SyncLaneRouter.lane(message.getRef(), message.getBranch()),
                    message.getJobId(), message.getPairName());
        }
        rabbitTemplate.convertAndSend(exchangeName, routingKey, message, m -> {
            m.getMessageProperties().setMessageId(messageId);
            m.getMessageProperties().setCorrelationId(message.getJobId().toString());
            m.getMessageProperties().setTimestamp(java.util.Date.from(Instant.now()));
            return m;
        });
    }
}
