package com.gitutility.service;

import com.gitutility.model.dto.DiffInspectOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffInspectionPipelineTest {

    @Test
    void quickPipelineHasPrepareAndCompareOnly() {
        DiffInspectionPipeline pipeline = DiffInspectionPipeline.forOptions(DiffInspectOptions.quick());
        pipeline.markCurrent(DiffInspectionPipeline.PREPARE);
        pipeline.markDone(DiffInspectionPipeline.PREPARE);
        pipeline.markCurrent(DiffInspectionPipeline.COMPARE_BRANCHES, "100 branches");
        pipeline.markDone(DiffInspectionPipeline.COMPARE_BRANCHES, "98 in sync");

        assertNotNull(pipeline.toMap().get("stages"));
        @SuppressWarnings("unchecked")
        var stages = (java.util.List<java.util.Map<String, Object>>) pipeline.toMap().get("stages");
        assertEquals(2, stages.size());
        assertEquals("done", stages.get(0).get("status"));
        assertEquals("compare_branches", stages.get(1).get("id"));
    }

    @Test
    void fullPipelineRunsLfsAfterMetadataStages() {
        DiffInspectionPipeline pipeline = DiffInspectionPipeline.forOptions(DiffInspectOptions.full());
        @SuppressWarnings("unchecked")
        var stages = (java.util.List<java.util.Map<String, Object>>) pipeline.toMap().get("stages");
        assertTrue(stages.size() >= 7);
        assertEquals("prepare", stages.get(0).get("id"));
        assertEquals("fetch_source", stages.get(1).get("id"));
        assertEquals("pr_metadata", stages.get(stages.size() - 3).get("id"));
        assertEquals("releases", stages.get(stages.size() - 2).get("id"));
        assertEquals("lfs", stages.get(stages.size() - 1).get("id"));
    }

    @Test
    void fullPipelineIncludesFetchAndMetadataStages() {
        DiffInspectionPipeline pipeline = DiffInspectionPipeline.forOptions(DiffInspectOptions.full());
        @SuppressWarnings("unchecked")
        var stages = (java.util.List<java.util.Map<String, Object>>) pipeline.toMap().get("stages");
        assertTrue(stages.size() >= 6);
        assertEquals("prepare", stages.get(0).get("id"));
        assertEquals("fetch_source", stages.get(1).get("id"));
    }
}
