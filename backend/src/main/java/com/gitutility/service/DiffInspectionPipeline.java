package com.gitutility.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gitutility.model.dto.DiffInspectOptions;

/**
 * Ephemeral pipeline for sync-diff / refresh inspection (not persisted on sync jobs).
 */
public class DiffInspectionPipeline {

    public static final String PREPARE = "prepare";
    public static final String FETCH_SOURCE = SyncPipelineState.FETCH_SOURCE;
    public static final String FETCH_DEST = SyncPipelineState.INSPECT_DEST;
    public static final String COMPARE_BRANCHES = "compare_branches";
    public static final String LFS = SyncPipelineState.LFS;
    public static final String PR_METADATA = SyncPipelineState.PR_METADATA;
    public static final String RELEASES = SyncPipelineState.RELEASES;

    private final List<SyncPipelineState.Stage> stages = new ArrayList<>();
    private String currentStageId;

    public static DiffInspectionPipeline forOptions(DiffInspectOptions inspect) {
        boolean fullRefresh = inspect.refresh();
        boolean metadata = inspect.includeMetadata();
        DiffInspectionPipeline pipeline = new DiffInspectionPipeline();
        pipeline.stages.add(stage(PREPARE, "Prepare local mirror", SyncPipelineState.PENDING));
        if (fullRefresh) {
            pipeline.stages.add(stage(FETCH_SOURCE, "Fetch source refs", SyncPipelineState.PENDING));
            pipeline.stages.add(stage(FETCH_DEST, "Fetch destination refs", SyncPipelineState.PENDING));
        }
        pipeline.stages.add(stage(COMPARE_BRANCHES, "Compare branches & refs", SyncPipelineState.PENDING));
        if (metadata) {
            pipeline.stages.add(stage(PR_METADATA, "Pull request metadata", SyncPipelineState.PENDING));
            pipeline.stages.add(stage(RELEASES, "Releases & tags", SyncPipelineState.PENDING));
            pipeline.stages.add(stage(LFS, "Scan Git LFS pointers", SyncPipelineState.PENDING));
        }
        return pipeline;
    }

    private static SyncPipelineState.Stage stage(String id, String label, String status) {
        return new SyncPipelineState.Stage(id, label, status);
    }

    public void markCurrent(String id) {
        markCurrent(id, null);
    }

    public void markCurrent(String id, String detail) {
        clearCurrent();
        SyncPipelineState.Stage stage = find(id);
        if (stage != null) {
            stage.status = SyncPipelineState.CURRENT;
            currentStageId = id;
            if (stage.startedAtMs == null) {
                stage.startedAtMs = System.currentTimeMillis();
            }
            if (detail != null) {
                stage.detail = detail;
            }
        }
    }

    public void markDone(String id) {
        markDone(id, null);
    }

    public void markDone(String id, String detail) {
        SyncPipelineState.Stage stage = find(id);
        if (stage != null) {
            stage.status = SyncPipelineState.DONE;
            if (detail != null) {
                stage.detail = detail;
            }
            finalizeDuration(stage);
        }
        if (id.equals(currentStageId)) {
            currentStageId = null;
        }
    }

    public void markSkipped(String id, String detail) {
        SyncPipelineState.Stage stage = find(id);
        if (stage != null) {
            stage.status = SyncPipelineState.SKIPPED;
            if (detail != null) {
                stage.detail = detail;
            }
            finalizeDuration(stage);
        }
        if (id.equals(currentStageId)) {
            currentStageId = null;
        }
    }

    public String currentLabel() {
        if (currentStageId == null) {
            return "Finishing...";
        }
        SyncPipelineState.Stage stage = find(currentStageId);
        return stage != null && stage.label != null ? stage.label : currentStageId;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("currentStageId", currentStageId);
        List<Map<String, Object>> stageMaps = new ArrayList<>();
        for (SyncPipelineState.Stage stage : stages) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", stage.id);
            sm.put("label", stage.label);
            sm.put("status", stage.status);
            if (stage.detail != null) {
                sm.put("detail", stage.detail);
            }
            if (stage.startedAtMs != null) {
                sm.put("startedAtMs", stage.startedAtMs);
            }
            if (stage.durationMs != null) {
                sm.put("durationMs", stage.durationMs);
            }
            stageMaps.add(sm);
        }
        root.put("stages", stageMaps);
        return root;
    }

    private SyncPipelineState.Stage find(String id) {
        for (SyncPipelineState.Stage stage : stages) {
            if (id.equals(stage.id)) {
                return stage;
            }
        }
        return null;
    }

    private void clearCurrent() {
        for (SyncPipelineState.Stage stage : stages) {
            if (SyncPipelineState.CURRENT.equals(stage.status)) {
                stage.status = SyncPipelineState.PENDING;
            }
        }
    }

    private static void finalizeDuration(SyncPipelineState.Stage stage) {
        if (stage.startedAtMs != null && stage.durationMs == null) {
            stage.durationMs = Math.max(0, System.currentTimeMillis() - stage.startedAtMs);
        }
    }
}
