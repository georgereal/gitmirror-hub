package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncLaneRouterTest {

    @Test
    void nullOrBlankRefIsFullMirror() {
        assertTrue(SyncLaneRouter.isFullMirror(null, "main"));
        assertTrue(SyncLaneRouter.isFullMirror("", "main"));
        assertTrue(SyncLaneRouter.isFullMirror("   ", "main"));
        assertEquals(SyncLaneRouter.LANE_FULL, SyncLaneRouter.lane(null, "main"));
    }

    @Test
    void wildcardBranchIsFullMirror() {
        assertTrue(SyncLaneRouter.isFullMirror("refs/heads/main", "*"));
    }

    @Test
    void specificRefIsIncremental() {
        assertFalse(SyncLaneRouter.isFullMirror("refs/heads/main", "main"));
        assertEquals(SyncLaneRouter.LANE_INCREMENTAL, SyncLaneRouter.lane("refs/heads/main", "main"));
    }

    @Test
    void routingKeyFollowsLane() {
        assertEquals("git.sync.key",
                SyncLaneRouter.routingKey(null, "*", "git.sync.key", "git.sync.incremental.key"));
        assertEquals("git.sync.incremental.key",
                SyncLaneRouter.routingKey("refs/heads/feat", "feat", "git.sync.key", "git.sync.incremental.key"));
    }

    @Test
    void eventNullDefaultsToFull() {
        assertTrue(SyncLaneRouter.isFullMirror((SyncEventMessage) null));
        assertTrue(SyncLaneRouter.isFullMirror(SyncEventMessage.builder().build()));
        assertFalse(SyncLaneRouter.isFullMirror(SyncEventMessage.builder()
                .ref("refs/heads/main")
                .branch("main")
                .build()));
    }

    @Test
    void pairMetadataOnlyOnFullMirrorJobs() {
        assertTrue(SyncLaneRouter.includePairMetadata(null));
        assertTrue(SyncLaneRouter.includePairMetadata(SyncEventMessage.builder().ref(null).branch("*").build()));
        assertFalse(SyncLaneRouter.includePairMetadata(SyncEventMessage.builder()
                .ref("refs/heads/main")
                .branch("main")
                .build()));
    }
}
