package com.gitutility.messaging.webhook.kafka;

/**
 * One Kafka incremental contract per process, chosen at startup.
 * {@code normalized-v1} is today's {@code IncrementalGitEvent}. {@code enriched} maps other JSON
 * onto that same object inside the listener. The two modes are not active together.
 */
public enum IncrementalEventFormat {
    NORMALIZED_V1,
    ENRICHED;

    public String wireId() {
        return switch (this) {
            case NORMALIZED_V1 -> "normalized-v1";
            case ENRICHED -> "enriched";
        };
    }

    public static IncrementalEventFormat from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMALIZED_V1;
        }
        String key = raw.trim().toLowerCase().replace('_', '-');
        return switch (key) {
            case "normalized-v1", "normalized", "1" -> NORMALIZED_V1;
            case "enriched", "2" -> ENRICHED;
            default -> throw new IllegalArgumentException(
                    "Unknown GIT_WEBHOOK_EVENT_FORMAT '" + raw + "' (supported: normalized-v1, enriched)");
        };
    }
}
