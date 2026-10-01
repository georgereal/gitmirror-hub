package com.gitutility.messaging.webhook.kafka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaOffsetSeekTest {

    @Test
    void earliestAndLatestUseLogBounds() {
        assertEquals(10, KafkaOffsetSeek.targetOffset("earliest", 10, 100, 80L, 50));
        assertEquals(100, KafkaOffsetSeek.targetOffset("latest", 10, 100, 80L, 50));
    }

    @Test
    void rewindSubtractsFromCommittedAndClampsToStart() {
        assertEquals(30, KafkaOffsetSeek.targetOffset("rewind", 10, 100, 80L, 50));
        assertEquals(10, KafkaOffsetSeek.targetOffset("rewind", 10, 100, 40L, 50));
    }

    @Test
    void rewindWithoutCommitUsesEnd() {
        assertEquals(50, KafkaOffsetSeek.targetOffset("rewind", 10, 100, null, 50));
    }

    @Test
    void normalizeModeAndRewindBy() {
        assertEquals("earliest", KafkaOffsetSeek.normalizeMode(" Earliest "));
        assertEquals(50, KafkaOffsetSeek.normalizeRewindBy(null));
        assertEquals(10_000, KafkaOffsetSeek.normalizeRewindBy(50_000));
        assertThrows(IllegalArgumentException.class, () -> KafkaOffsetSeek.normalizeMode("middle"));
        assertThrows(IllegalArgumentException.class, () -> KafkaOffsetSeek.normalizeRewindBy(0));
    }
}
