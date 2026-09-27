package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
@WebhookBusConditions.OnKafka
public class KafkaWebhookConfig {

    @Bean
    public NewTopic webhookIncrementalTopic(
            @Value("${git-utility.webhook-bus.kafka.incremental-topic}") String topic,
            @Value("${git-utility.webhook-bus.kafka.partitions:32}") int partitions,
            @Value("${git-utility.webhook-bus.kafka.replication-factor:1}") short replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> webhookKafkaListenerContainerFactory(
            KafkaProperties properties,
            KafkaWebhookPublisher publisher,
            @Value("${git-utility.webhook-bus.kafka.listener-concurrency:4}") int concurrency,
            @Value("${git-utility.queue.max-retry-attempts:3}") int maxAttempts) {
        Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setConcurrency(Math.max(1, concurrency));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        long retriesAfterFirst = Math.max(0, maxAttempts - 1L);
        ConsumerRecordRecoverer recover = (record, exception) -> {
            if (!causedByRedelivery(exception)) {
                return;
            }
            String key = record.key() == null ? null : record.key().toString();
            String value = record.value() == null ? null : record.value().toString();
            publisher.deadLetterRaw(key, value, exception.getMessage());
        };
        DefaultErrorHandler errors = new DefaultErrorHandler(recover, new FixedBackOff(3_000L, retriesAfterFirst));
        errors.addRetryableExceptions(EnrichedEventRedelivery.class);
        errors.addNotRetryableExceptions(Exception.class);
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    private static boolean causedByRedelivery(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof EnrichedEventRedelivery) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
