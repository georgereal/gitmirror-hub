package com.gitutility.messaging.webhook;

/**
 * Incremental git-event bus. Independent of {@code GIT_MESSAGING_PROVIDER}
 * (operator full mirrors). Canonical wire values: {@code off}, {@code kafka}, {@code rabbitmq}.
 */
public enum WebhookBusProvider {
    OFF,
    KAFKA,
    RABBITMQ;

    public String wireId() {
        return switch (this) {
            case OFF -> "off";
            case KAFKA -> "kafka";
            case RABBITMQ -> "rabbitmq";
        };
    }

    public boolean isActive() {
        return this != OFF;
    }

    public static WebhookBusProvider from(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        String key = raw.trim().toLowerCase().replace('_', '-');
        return switch (key) {
            case "off", "none", "disabled" -> OFF;
            case "kafka" -> KAFKA;
            case "rabbitmq", "rabbit", "rabbit-mq", "amqp" -> RABBITMQ;
            default -> throw new IllegalArgumentException(
                    "Unknown GIT_WEBHOOK_BUS_PROVIDER '" + raw + "' (supported: off, kafka, rabbitmq)");
        };
    }
}
