package com.gitutility.messaging;

/**
 * Pluggable messaging fabric. Canonical wire values (env / YAML):
 * {@code rabbitmq} (default), {@code kafka} (reserved), {@code none} (in-process, no broker).
 */
public enum MessagingProvider {
    /** Durable AMQP (CloudAMQP / RabbitMQ). */
    RABBITMQ,
    /** Reserved — Kafka adapter not shipped yet; selecting this fails fast at startup. */
    KAFKA,
    /** No external broker; sync runs in-process (enterprise bring-up / single-node demos). */
    NONE;

    /** Canonical lowercase token for env, YAML, and API responses. */
    public String wireId() {
        return switch (this) {
            case RABBITMQ -> "rabbitmq";
            case KAFKA -> "kafka";
            case NONE -> "none";
        };
    }

    public static MessagingProvider from(String raw) {
        if (raw == null || raw.isBlank()) {
            return RABBITMQ;
        }
        String key = raw.trim().toLowerCase().replace('_', '-');
        return switch (key) {
            case "rabbitmq", "rabbit", "rabbit-mq", "amqp" -> RABBITMQ;
            case "kafka" -> KAFKA;
            case "none", "local", "in-process", "inprocess", "inline" -> NONE;
            default -> throw new IllegalArgumentException(
                    "Unknown git-utility.messaging.provider '" + raw
                            + "' (supported: rabbitmq, kafka, none)");
        };
    }

    public boolean isBrokerBacked() {
        return this == RABBITMQ || this == KAFKA;
    }

    public boolean isImplemented() {
        return this == RABBITMQ || this == NONE;
    }
}
