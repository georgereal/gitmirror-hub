package com.gitutility.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerRuntimeRegistryTest {

    @Test
    void bindUnbindTracksUnackedPerLane() {
        ConsumerRuntimeRegistry registry = new ConsumerRuntimeRegistry();
        assertEquals(0, registry.unackedCount(SyncLaneRouter.LANE_FULL));

        registry.bind(SyncLaneRouter.LANE_FULL, SyncLaneRouter.FULL_CONSUMER_ID, 12L, "vscode", "refs/heads/main");
        assertEquals(1, registry.unackedCount(SyncLaneRouter.LANE_FULL));
        assertEquals(0, registry.unackedCount(SyncLaneRouter.LANE_INCREMENTAL));

        ConsumerRuntimeRegistry.Slot slot = registry.slotsForLane(SyncLaneRouter.LANE_FULL).get(0);
        assertEquals(12L, slot.getJobId());
        assertEquals("vscode", slot.getPairName());
        assertTrue(slot.isThreadAlive());
        assertEquals(ConsumerRuntimeRegistry.SlotState.PROCESSING, slot.getState());

        registry.markSkipping();
        assertEquals(ConsumerRuntimeRegistry.SlotState.SKIPPING, registry.slotsForLane(SyncLaneRouter.LANE_FULL).get(0).getState());

        registry.unbind();
        assertEquals(0, registry.unackedCount(SyncLaneRouter.LANE_FULL));
        assertTrue(registry.allSlots().isEmpty());
    }
}
