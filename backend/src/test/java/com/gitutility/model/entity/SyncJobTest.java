package com.gitutility.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyncJobTest {

    @Test
    void clipLeavesShortValuesUnchanged() {
        assertNull(SyncJob.clip(null, 1000));
        assertEquals("feat: short", SyncJob.clip("feat: short", 1000));
        assertEquals("a".repeat(1000), SyncJob.clip("a".repeat(1000), 1000));
    }

    @Test
    void clipTruncatesOversizedCommitBodies() {
        String clipped = SyncJob.clip("a".repeat(5209), 1000);
        assertEquals(1000, clipped.length());
        assertEquals("\u2026", clipped.substring(999));
    }
}
