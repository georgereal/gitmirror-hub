package com.gitutility.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitutility.model.enums.SyncCheckpointStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outer recipe of a mirror run. Object-counting JGit phases nest under the current Git step.
 */
public class SyncPipelineState {

    public static final String FAST_PATH = "fast_path";
    public static final String VERIFY_DEST = "verify_dest";
    public static final String FETCH_SOURCE = "fetch_source";
    public static final String INSPECT_DEST = "inspect_dest";
    public static final String CONFLICT_CHECK = "conflict_check";
    public static final String PUSH_DEST = "push_dest";
    public static final String LFS = "lfs";
    public static final String PR_METADATA = "pr_metadata";
    public static final String RELEASES = "releases";

    public static final List<String> STAGE_ORDER = List.of(
            FAST_PATH, VERIFY_DEST, FETCH_SOURCE, INSPECT_DEST, CONFLICT_CHECK,
            PUSH_DEST, PR_METADATA, RELEASES, LFS
    );

    public static final String PENDING = "pending";
    public static final String CURRENT = "current";
    public static final String DONE = "done";
    public static final String SKIPPED = "skipped";
    public static final String FAILED = "failed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class Stage {
        public String id;
        public String label;
        public String status;
        public String detail;
        public Long startedAtMs;
        public Long durationMs;

        public Stage() {
        }

        public Stage(String id, String label, String status) {
            this.id = id;
            this.label = label;
            this.status = status;
        }
    }

    private final List<Stage> stages = new ArrayList<>();
    private String currentStageId;

    public static SyncPipelineState initial() {
        SyncPipelineState state = new SyncPipelineState();
        state.stages.add(new Stage(FAST_PATH, "Fast-path check", PENDING));
        state.stages.add(new Stage(VERIFY_DEST, "Verify destination write", PENDING));
        state.stages.add(new Stage(FETCH_SOURCE, "Fetch source refs and objects", PENDING));
        state.stages.add(new Stage(INSPECT_DEST, "Inspect destination refs", PENDING));
        state.stages.add(new Stage(CONFLICT_CHECK, "Conflict / fast-forward check", PENDING));
        state.stages.add(new Stage(PUSH_DEST, "Push to destination", PENDING));
        state.stages.add(new Stage(PR_METADATA, "PR metadata", PENDING));
        state.stages.add(new Stage(RELEASES, "Releases / CI", PENDING));
        state.stages.add(new Stage(LFS, "Git LFS", PENDING));
        return state;
    }

    public static SyncPipelineState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return initial();
        }
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {});
            SyncPipelineState state = initial();
            Object current = root.get("currentStageId");
            if (current instanceof String currentId && !currentId.isBlank()) {
                state.currentStageId = currentId;
            }
            Object stageList = root.get("stages");
            if (stageList instanceof List<?> rawStages) {
                for (Object raw : rawStages) {
                    if (!(raw instanceof Map<?, ?> sm)) {
                        continue;
                    }
                    Object idObj = sm.get("id");
                    if (!(idObj instanceof String id)) {
                        continue;
                    }
                    Stage stage = state.find(id);
                    if (stage == null) {
                        continue;
                    }
                    Object status = sm.get("status");
                    if (status instanceof String statusStr) {
                        stage.status = statusStr;
                    }
                    Object detail = sm.get("detail");
                    if (detail instanceof String detailStr) {
                        stage.detail = detailStr;
                    }
                    Object startedAt = sm.get("startedAtMs");
                    if (startedAt instanceof Number startedNum) {
                        stage.startedAtMs = startedNum.longValue();
                    }
                    Object duration = sm.get("durationMs");
                    if (duration instanceof Number durationNum) {
                        stage.durationMs = durationNum.longValue();
                    }
                }
            }
            return state;
        } catch (Exception e) {
            return initial();
        }
    }

    public static int stageIndex(String stageId) {
        if (stageId == null) {
            return 0;
        }
        int idx = STAGE_ORDER.indexOf(stageId);
        return idx >= 0 ? idx : 0;
    }

    public static boolean isAtOrAfter(String stageId, String pivotStageId) {
        return stageIndex(stageId) >= stageIndex(pivotStageId);
    }

    public static String nextStageAfter(String stageId) {
        int idx = stageIndex(stageId);
        if (idx + 1 < STAGE_ORDER.size()) {
            return STAGE_ORDER.get(idx + 1);
        }
        return null;
    }

    public boolean isStageSettled(String id) {
        Stage stage = find(id);
        return stage != null && (DONE.equals(stage.status) || SKIPPED.equals(stage.status));
    }

    public String firstPendingStageId() {
        for (String id : STAGE_ORDER) {
            Stage stage = find(id);
            if (stage != null && PENDING.equals(stage.status)) {
                return id;
            }
        }
        return null;
    }

    /** First stage that is not done or skipped — used as the job resume cursor after skip. */
    public String firstRunnableStageId() {
        for (String id : STAGE_ORDER) {
            if (!isStageSettled(id)) {
                return id;
            }
        }
        return null;
    }

    public List<Stage> getStages() {
        return stages;
    }

    public String getCurrentStageId() {
        return currentStageId;
    }

    /** Human label for the live progress badge; not the raw stage id (`lfs`). */
    public String currentLabel() {
        if (currentStageId == null) {
            return "Finishing...";
        }
        Stage stage = find(currentStageId);
        if (stage != null && stage.label != null && !stage.label.isBlank()) {
            return stage.label;
        }
        return currentStageId;
    }

    public void markCurrent(String id) {
        Stage stage = find(id);
        if (stage != null && CURRENT.equals(stage.status) && id.equals(currentStageId)) {
            return;
        }
        clearCurrent();
        if (stage != null) {
            stage.status = CURRENT;
            currentStageId = id;
            if (stage.startedAtMs == null) {
                stage.startedAtMs = System.currentTimeMillis();
            }
        }
    }

    public void markCurrent(String id, String detail) {
        markCurrent(id);
        Stage stage = find(id);
        if (stage != null && detail != null) {
            stage.detail = detail;
        }
    }

    public void markDone(String id) {
        markDone(id, null);
    }

    public void markDone(String id, String detail) {
        Stage stage = find(id);
        if (stage != null) {
            stage.status = DONE;
            if (detail != null) {
                stage.detail = detail;
            }
            finalizeDuration(stage);
        }
        if (id.equals(currentStageId)) {
            currentStageId = null;
        }
    }

    public void markSkipped(String id) {
        markSkipped(id, null);
    }

    public void markSkipped(String id, String detail) {
        Stage stage = find(id);
        if (stage != null && !DONE.equals(stage.status) && !FAILED.equals(stage.status)) {
            stage.status = SKIPPED;
            if (detail != null) {
                stage.detail = detail;
            }
            finalizeDuration(stage);
        }
        if (id.equals(currentStageId)) {
            currentStageId = null;
        }
    }

    public void markFailed(String id, String reason) {
        Stage stage = find(id);
        if (stage != null) {
            stage.status = FAILED;
            stage.detail = reason;
            finalizeDuration(stage);
        }
        currentStageId = id;
    }

    public void skipRemainingAfterFastPath() {
        markDone(FAST_PATH, "Commit already in local DAG");
        markSkipped(VERIFY_DEST, "Fast-path");
        markSkipped(FETCH_SOURCE, "Fast-path");
        markSkipped(INSPECT_DEST, "Fast-path");
        markSkipped(CONFLICT_CHECK, "Fast-path");
        markSkipped(PUSH_DEST, "Fast-path");
        markSkipped(PR_METADATA, "Fast-path");
        markSkipped(RELEASES, "Fast-path");
        markSkipped(LFS, "Fast-path");
        currentStageId = null;
    }

    /**
     * @deprecated Pair-level checkpoints are auxiliary only; job {@link #fromJson(String)} drives resume.
     */
    @Deprecated
    public void applyResumeCheckpoint(SyncCheckpointStage checkpoint) {
        if (checkpoint == null || checkpoint == SyncCheckpointStage.NONE) {
            return;
        }
        markDone(FAST_PATH, "Resumed");
        markDone(VERIFY_DEST, "Resumed");
        if (checkpoint.ordinal() >= SyncCheckpointStage.PUSH_DONE.ordinal()) {
            markSkipped(FETCH_SOURCE, "Checkpoint — packs on disk");
            markDone(INSPECT_DEST, "Resumed");
            markDone(CONFLICT_CHECK, "Resumed");
            markDone(PUSH_DEST, "Checkpoint resume");
        }
        if (checkpoint.ordinal() >= SyncCheckpointStage.LFS_DISCOVERY_DONE.ordinal()) {
            markDone(PR_METADATA, "Resumed");
            markDone(RELEASES, "Resumed");
            if (checkpoint == SyncCheckpointStage.LFS_TRANSFER_PARTIAL) {
                markCurrent(LFS, "Resuming blob transfer");
            } else if (checkpoint.ordinal() >= SyncCheckpointStage.GIT_SYNC_DONE.ordinal()) {
                markDone(LFS, "Completed on prior run");
            } else {
                markDone(LFS, "Discovery cached");
            }
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("currentStageId", currentStageId);
        List<Map<String, Object>> stageMaps = new ArrayList<>();
        for (Stage stage : stages) {
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
        map.put("stages", stageMaps);
        return map;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(toMap());
        } catch (Exception e) {
            return "{}";
        }
    }

    private void finalizeDuration(Stage stage) {
        if (stage != null && stage.startedAtMs != null && stage.durationMs == null) {
            stage.durationMs = Math.max(0, System.currentTimeMillis() - stage.startedAtMs);
        }
    }

    private void clearCurrent() {
        for (Stage stage : stages) {
            if (CURRENT.equals(stage.status)) {
                stage.status = PENDING;
            }
        }
    }

    private Stage find(String id) {
        for (Stage stage : stages) {
            if (id.equals(stage.id)) {
                return stage;
            }
        }
        return null;
    }
}
