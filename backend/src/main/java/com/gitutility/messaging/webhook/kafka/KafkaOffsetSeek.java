package com.gitutility.messaging.webhook.kafka;

/**
 * Pure offset math for webhook Kafka seek / replay.
 */
public final class KafkaOffsetSeek {

    public static final int DEFAULT_REWIND_BY = 50;
    public static final int MAX_REWIND_BY = 10_000;

    private KafkaOffsetSeek() {
    }

    public static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("mode is required (earliest, latest, or rewind)");
        }
        String mode = raw.trim().toLowerCase();
        if (!"earliest".equals(mode) && !"latest".equals(mode) && !"rewind".equals(mode)) {
            throw new IllegalArgumentException("mode must be earliest, latest, or rewind (was '" + raw + "')");
        }
        return mode;
    }

    public static int normalizeRewindBy(Integer rewindBy) {
        int value = rewindBy == null ? DEFAULT_REWIND_BY : rewindBy;
        if (value < 1) {
            throw new IllegalArgumentException("rewindBy must be at least 1");
        }
        return Math.min(value, MAX_REWIND_BY);
    }

    /**
     * @param committed committed offset, or {@code null} when the group has never committed this partition
     */
    public static long targetOffset(String mode, long start, long end, Long committed, int rewindBy) {
        return switch (mode) {
            case "earliest" -> start;
            case "latest" -> end;
            case "rewind" -> {
                long from = committed == null ? end : committed;
                yield Math.max(start, from - rewindBy);
            }
            default -> throw new IllegalArgumentException("mode must be earliest, latest, or rewind");
        };
    }
}
