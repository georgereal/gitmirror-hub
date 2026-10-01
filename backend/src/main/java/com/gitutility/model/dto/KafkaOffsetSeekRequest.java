package com.gitutility.model.dto;

/**
 * Move the webhook Kafka consumer-group pointer.
 * {@code mode}: {@code earliest}, {@code latest}, or {@code rewind}.
 */
public record KafkaOffsetSeekRequest(
        String mode,
        Integer rewindBy,
        Integer partition
) {
}
