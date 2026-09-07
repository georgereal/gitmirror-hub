package com.gitutility.model.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobStageProgressPrCheckpointTest {

    @Test
    void prListCursorRoundTripsThroughJson() {
        JobStageProgress progress = JobStageProgress.builder()
                .prListCursor("Y3Vyc29yOjEwMA==")
                .prListComplete(false)
                .build();

        JobStageProgress restored = JobStageProgress.fromJson(progress.toJson());

        assertEquals("Y3Vyc29yOjEwMA==", restored.getPrListCursor());
        assertEquals(false, restored.getPrListComplete());
    }
}
