package com.gitutility.controller;

import com.gitutility.model.dto.JobUsageResponse;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.service.SyncJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class SyncJobController {

    private final SyncJobService syncJobService;

    @GetMapping
    public ResponseEntity<Page<SyncJob>> getJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) SyncStatus status,
            @RequestParam(required = false) String mappingId,
            @RequestParam(required = false) TriggerType triggerType,
            @RequestParam(required = false) String lane) {
        return ResponseEntity.ok(syncJobService.getAllJobs(
                PageRequest.of(page, size), status, mappingId, triggerType, lane));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<SyncJob>> getRecentJobs() {
        return ResponseEntity.ok(syncJobService.getRecentJobs());
    }

    @GetMapping("/usage")
    public ResponseEntity<JobUsageResponse> getJobUsage(
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "40") int limit) {
        return ResponseEntity.ok(syncJobService.getJobUsage(since, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SyncJob> getJobById(@PathVariable String id) {
        return syncJobService.getJobById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<List<SyncAuditLog>> getJobLogs(@PathVariable String id) {
        return ResponseEntity.ok(syncJobService.getAuditLogsForJob(id));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        return ResponseEntity.ok(syncJobService.getDashboardStats());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<SyncJob> retryJob(@PathVariable String id) {
        return ResponseEntity.accepted().body(syncJobService.retryJob(id));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<SyncJob> resumeJob(@PathVariable String id) {
        return ResponseEntity.accepted().body(syncJobService.resumeJob(id));
    }

    @PostMapping("/dispatch")
    public ResponseEntity<Map<String, Object>> dispatchJobs(@RequestBody Map<String, List<String>> body) {
        List<String> jobIds = body != null ? body.get("jobIds") : null;
        return ResponseEntity.accepted().body(syncJobService.dispatchJobs(jobIds));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<SyncJob> pauseJob(@PathVariable String id) {
        return ResponseEntity.ok(syncJobService.pauseJob(id));
    }

    @PostMapping("/{id}/skip-stage")
    public ResponseEntity<SyncJob> skipJobStage(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String stageId = body != null ? body.get("stageId") : null;
        return ResponseEntity.ok(syncJobService.skipJobStage(id, stageId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<SyncJob> cancelJob(@PathVariable String id) {
        return ResponseEntity.ok(syncJobService.cancelJob(id));
    }

    @PostMapping("/cancel-queued")
    public ResponseEntity<Map<String, Object>> cancelQueuedJobs(
            @RequestParam(required = false) String mappingId) {
        int count = syncJobService.cancelQueuedJobs(mappingId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "cancelledCount", count,
                "message", count + " queued job(s) cancelled"
                        + (mappingId != null ? " for mapping " + mappingId : "")
        ));
    }
}
