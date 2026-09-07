package com.gitutility.service;

import com.gitutility.model.dto.DiffInspectOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DiffInspectionProgressService {

    private static final long BROADCAST_THROTTLE_MS = 400;

    private final WebSocketNotificationService webSocketNotificationService;
    private final Map<Long, Long> lastBroadcastMs = new ConcurrentHashMap<>();

    public Session open(Long mappingId, DiffInspectOptions inspect) {
        return new Session(mappingId, DiffInspectionPipeline.forOptions(inspect));
    }

    public final class Session {
        private final Long mappingId;
        private final DiffInspectionPipeline pipeline;
        private int inSync;
        private int pending;
        private int diverged;
        private int destOnly;
        private boolean closed;

        private Session(Long mappingId, DiffInspectionPipeline pipeline) {
            this.mappingId = mappingId;
            this.pipeline = pipeline;
        }

        public DiffInspectionPipeline pipeline() {
            return pipeline;
        }

        public void markCurrent(String stageId) {
            markCurrent(stageId, null);
        }

        public void markCurrent(String stageId, String detail) {
            pipeline.markCurrent(stageId, detail);
            broadcast(stageId, 0, 0, detail != null ? detail : pipeline.currentLabel(), true);
        }

        public void markDone(String stageId) {
            markDone(stageId, null);
        }

        public void markDone(String stageId, String detail) {
            pipeline.markDone(stageId, detail);
            broadcast(stageId, 0, 0, detail != null ? detail : "Complete", true);
        }

        public void markSkipped(String stageId, String detail) {
            pipeline.markSkipped(stageId, detail);
            broadcast(stageId, 0, 0, detail, true);
        }

        public void branchProgress(int current, int total) {
            String detail = String.format("%d/%d branches · %d in sync, %d pending, %d diverged, %d dest-only",
                    current, total, inSync, pending, diverged, destOnly);
            pipeline.markCurrent(DiffInspectionPipeline.COMPARE_BRANCHES, detail);
            broadcast(DiffInspectionPipeline.COMPARE_BRANCHES, current, total, detail, false);
        }

        public void updateBranchCounts(int inSync, int pending, int diverged, int destOnly) {
            this.inSync = inSync;
            this.pending = pending;
            this.diverged = diverged;
            this.destOnly = destOnly;
        }

        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            lastBroadcastMs.remove(mappingId);
            webSocketNotificationService.notifyDiffProgress(
                    mappingId, "complete", 0, 0, "Inspection complete", pipeline.toMap(), countsMap(), true);
        }

        private void broadcast(String phase, int current, int total, String message, boolean force) {
            long now = System.currentTimeMillis();
            Long last = lastBroadcastMs.get(mappingId);
            if (!force && last != null && now - last < BROADCAST_THROTTLE_MS) {
                return;
            }
            lastBroadcastMs.put(mappingId, now);
            webSocketNotificationService.notifyDiffProgress(
                    mappingId, phase, current, total, message, pipeline.toMap(), countsMap(), false);
        }

        private Map<String, Object> countsMap() {
            Map<String, Object> counts = new HashMap<>();
            counts.put("inSync", inSync);
            counts.put("pending", pending);
            counts.put("diverged", diverged);
            counts.put("destOnly", destOnly);
            return counts;
        }
    }
}
