package com.gitutility.messaging.webhook.kafka;

/**
 * Feature {@code enriched} does not write a record back to Kafka.
 * The listener leaves the original enriched record uncommitted so the broker redelivers those bytes.
 */
public class EnrichedEventRedelivery extends RuntimeException {
    public EnrichedEventRedelivery(String message) {
        super(message);
    }
}
