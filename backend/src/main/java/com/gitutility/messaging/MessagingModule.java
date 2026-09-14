package com.gitutility.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessagingModule {

    @Value("${git-utility.messaging.provider:rabbitmq}")
    private String providerProperty;

    @Value("${git-utility.messaging.none.worker-threads:8}")
    private int noneWorkerThreads;

    public MessagingProvider provider() {
        return MessagingProvider.from(providerProperty);
    }

    public MessagingDescriptor descriptor() {
        return switch (provider()) {
            case RABBITMQ -> MessagingDescriptor.builder()
                    .provider(MessagingProvider.RABBITMQ)
                    .displayName("RabbitMQ")
                    .description("Durable AMQP lanes with DLQ, purge, and multi-pod consumers.")
                    .durableBroker(true)
                    .supportsQueueManager(true)
                    .supportsDlq(true)
                    .supportsPurge(true)
                    .supportsPauseConsumers(true)
                    .supportsInboundBrokerQueue(true)
                    .workerThreads(null)
                    .build();
            case KAFKA -> MessagingDescriptor.builder()
                    .provider(MessagingProvider.KAFKA)
                    .displayName("Kafka")
                    .description("Reserved — Kafka adapter not implemented yet.")
                    .durableBroker(true)
                    .supportsQueueManager(true)
                    .supportsDlq(true)
                    .supportsPurge(true)
                    .supportsPauseConsumers(true)
                    .supportsInboundBrokerQueue(true)
                    .workerThreads(null)
                    .build();
            case NONE -> MessagingDescriptor.builder()
                    .provider(MessagingProvider.NONE)
                    .displayName("None (in-process)")
                    .description("No external broker — sync jobs run on this JVM. Use for bring-up without AMQP; not for multi-pod webhook durability.")
                    .durableBroker(false)
                    .supportsQueueManager(false)
                    .supportsDlq(false)
                    .supportsPurge(false)
                    .supportsPauseConsumers(true)
                    .supportsInboundBrokerQueue(false)
                    .workerThreads(Math.max(1, noneWorkerThreads))
                    .build();
        };
    }
}
