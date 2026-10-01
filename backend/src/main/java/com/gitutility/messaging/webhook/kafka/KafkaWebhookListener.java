package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.messaging.webhook.WebhookBusControls;
import com.gitutility.messaging.webhook.WebhookIncrementalService;
import com.gitutility.model.dto.IncrementalGitEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@WebhookBusConditions.OnKafka
@RequiredArgsConstructor
@Slf4j
public class KafkaWebhookListener implements WebhookBusControls {

    public static final String LISTENER_ID = "webhookIncrementalKafka";

    private final WebhookIncrementalService webhookIncrementalService;
    private final KafkaWebhookPublisher publisher;
    private final KafkaIncrementalDecoder decoder;
    private final KafkaValueCodec valueCodec;
    private final KafkaListenerEndpointRegistry registry;

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${git-utility.webhook-bus.kafka.incremental-topic}",
            groupId = "${git-utility.webhook-bus.kafka.group-id}",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onRecord(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        String json;
        try {
            json = valueCodec.toJson(record.topic(), record.value());
        } catch (IncrementalEventDecodeException e) {
            log.warn("Discarding unreadable incremental record on {}: {}", record.topic(), e.getMessage());
            publisher.deadLetterRaw(record.key(), binaryPlaceholder(record.value()), e.getMessage());
            acknowledgment.acknowledge();
            return;
        }
        IncrementalGitEvent event;
        try {
            event = decoder.decode(json, schemaVersion(record));
        } catch (IncrementalEventDecodeException e) {
            log.warn("Discarding unreadable incremental record on {}: {}", record.topic(), e.getMessage());
            publisher.deadLetterRaw(record.key(), json, e.getMessage());
            acknowledgment.acknowledge();
            return;
        }
        event.setSourceMessage(cap(json));
        webhookIncrementalService.handle(event);
        acknowledgment.acknowledge();
    }

    private static String schemaVersion(ConsumerRecord<String, byte[]> record) {
        String version = header(record, "schemaVersion");
        return version != null ? version : header(record, "schema-version");
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length == 0) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
    }

    static String cap(String value) {
        if (value == null || value.length() <= 16_000) {
            return value;
        }
        return value.substring(0, 15_999) + "\u2026";
    }

    private static String binaryPlaceholder(byte[] value) {
        int len = value == null ? 0 : value.length;
        return "{\"error\":\"binary record could not be decoded\",\"bytes\":" + len + "}";
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
