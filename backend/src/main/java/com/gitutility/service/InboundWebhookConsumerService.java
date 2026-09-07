package com.gitutility.service;

import com.gitutility.model.dto.InboundWebhookMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InboundWebhookConsumerService {

    private final WebhookIngestionService webhookIngestionService;
    private final ConsumerRuntimeRegistry consumerRuntimeRegistry;

    @RabbitListener(
            id = SyncLaneRouter.INBOUND_CONSUMER_ID,
            queues = "${git-utility.queue.inbound-queue:git.sync.inbound.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeInboundWebhook(InboundWebhookMessage message) {
        String pair = message.getProvider() != null ? message.getProvider() : "inbound";
        if (message.getEventType() != null) {
            pair = pair + " · " + message.getEventType();
        }
        if (consumerRuntimeRegistry != null) {
            consumerRuntimeRegistry.bind(
                    SyncLaneRouter.LANE_INBOUND,
                    SyncLaneRouter.INBOUND_CONSUMER_ID,
                    message.getMappingId(),
                    pair,
                    message.getDeliveryId()
            );
        }
        try {
            log.info("Received inbound webhook from serverless worker via AMQP for provider: {}",
                    message.getProvider());
            webhookIngestionService.processInboundMessage(message);
        } finally {
            if (consumerRuntimeRegistry != null) {
                consumerRuntimeRegistry.unbind();
            }
        }
    }
}
