package com.gitutility.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncPipelineStateTest {

    @Test
    void fromJsonRestoresSettledStages() {
        SyncPipelineState original = SyncPipelineState.initial();
        original.markDone(SyncPipelineState.FETCH_SOURCE, "Authenticated");
        original.markDone(SyncPipelineState.PUSH_DEST, "49 batches");
        original.markCurrent(SyncPipelineState.PR_METADATA, "evaluating");

        SyncPipelineState restored = SyncPipelineState.fromJson(original.toJson());

        assertTrue(restored.isStageSettled(SyncPipelineState.FETCH_SOURCE));
        assertTrue(restored.isStageSettled(SyncPipelineState.PUSH_DEST));
        assertEquals(SyncPipelineState.PR_METADATA, restored.getCurrentStageId());
        assertFalse(restored.isStageSettled(SyncPipelineState.PR_METADATA));
    }

    @Test
    void markSkippedClearsCurrentSoUiDoesNotStayOnLfs() {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        pipeline.markCurrent(SyncPipelineState.LFS);
        assertEquals(SyncPipelineState.LFS, pipeline.getCurrentStageId());
        assertEquals("Git LFS", pipeline.currentLabel());

        pipeline.markSkipped(SyncPipelineState.LFS, "No LFS pointers");
        assertNull(pipeline.getCurrentStageId());
        assertEquals("Finishing...", pipeline.currentLabel());
        assertEquals(SyncPipelineState.SKIPPED, pipeline.getStages().stream()
                .filter(s -> SyncPipelineState.LFS.equals(s.id))
                .findFirst()
                .orElseThrow()
                .status);
    }

    @Test
    void fullMirrorPipelineRunsLfsLast() {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        List<SyncPipelineState.Stage> stages = pipeline.getStages();
        assertEquals(SyncPipelineState.PR_METADATA, stages.get(stages.size() - 3).id);
        assertEquals(SyncPipelineState.RELEASES, stages.get(stages.size() - 2).id);
        assertEquals(SyncPipelineState.LFS, stages.get(stages.size() - 1).id);
    }

    @Test
    void recordsStageDurationWhenMarkedDone() throws InterruptedException {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        pipeline.markCurrent(SyncPipelineState.FETCH_SOURCE);
        Thread.sleep(15);
        pipeline.markDone(SyncPipelineState.FETCH_SOURCE, "OK");

        SyncPipelineState.Stage stage = pipeline.getStages().stream()
                .filter(s -> SyncPipelineState.FETCH_SOURCE.equals(s.id))
                .findFirst()
                .orElseThrow();

        assertNotNull(stage.startedAtMs);
        assertNotNull(stage.durationMs);
        assertTrue(stage.durationMs >= 10);
        assertNotNull(pipeline.toMap().get("stages"));
    }
}
