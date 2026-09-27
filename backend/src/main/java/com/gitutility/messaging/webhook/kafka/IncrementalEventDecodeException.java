package com.gitutility.messaging.webhook.kafka;

/** The Kafka record is not usable under the active event format. */
public class IncrementalEventDecodeException extends RuntimeException {
    public IncrementalEventDecodeException(String message) {
        super(message);
    }
}
