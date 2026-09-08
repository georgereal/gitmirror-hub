package com.gitutility.messaging.rabbit;

import com.gitutility.messaging.MessagingConditions;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.SyncLaneRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * AMQP listeners for execution lanes. Loaded only when messaging provider is {@code rabbit}.
 */
@Component
@MessagingConditions.OnRabbitMq
@RequiredArgsConstructor
public class RabbitLaneConsumers {

    private final QueueConsumerService queueConsumerService;

    @RabbitListener(
            id = SyncLaneRouter.FULL_CONSUMER_ID,
            queues = "${git-utility.queue.main-queue:git.sync.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeFullMirrorEvent(SyncEventMessage event) throws Exception {
        queueConsumerService.consumeSyncEvent(event, SyncLaneRouter.LANE_FULL, SyncLaneRouter.FULL_CONSUMER_ID);
    }

    @RabbitListener(
            id = SyncLaneRouter.INCREMENTAL_CONSUMER_ID,
            queues = "${git-utility.queue.incremental-queue:git.sync.incremental.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeIncrementalEvent(SyncEventMessage event) throws Exception {
        queueConsumerService.consumeSyncEvent(
                event, SyncLaneRouter.LANE_INCREMENTAL, SyncLaneRouter.INCREMENTAL_CONSUMER_ID);
    }
}
