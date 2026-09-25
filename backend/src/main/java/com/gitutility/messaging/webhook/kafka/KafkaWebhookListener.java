package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.messaging.webhook.WebhookBusControls;
import com.gitutility.messaging.webhook.WebhookIncrementalService;
import com.gitutility.model.dto.IncrementalGitEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@WebhookBusConditions.OnKafka
@RequiredArgsConstructor
@Slf4j
public class KafkaWebhookListener implements WebhookBusControls {

    public static final String LISTENER_ID = "webhookIncrementalKafka";

    private final WebhookIncrementalService webhookIncrementalService;
    private final KafkaWebhookPublisher publisher;
    private final ObjectMapper objectMapper;
    private final KafkaListenerEndpointRegistry registry;

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${git-utility.webhook-bus.kafka.incremental-topic}",
            groupId = "${git-utility.webhook-bus.kafka.group-id}",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onRecord(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        IncrementalGitEvent event;
        try {
            event = objectMapper.readValue(record.value(), IncrementalGitEvent.class);
        } catch (Exception e) {
            log.warn("Discarding unreadable incremental record on {}: {}", record.topic(), e.getMessage());
            publisher.deadLetterRaw(record.key(), record.value(), e.getMessage());
            acknowledgment.acknowledge();
            return;
        }
        webhookIncrementalService.handle(event);
        acknowledgment.acknowledge();
    }

    @Override
    public void pause() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && !container.isPauseRequested()) {
            container.pause();
            log.info("Paused Kafka incremental webhook listener");
        }
    }

    @Override
    public void resume() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container != null) {
            container.resume();
            log.info("Resumed Kafka incremental webhook listener");
        }
    }
}
