package com.gitutility.controller;

import com.gitutility.model.entity.BulkSubmission;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.service.BulkSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Bulk migration submission lifecycle: query a submission's per-row outcomes (including
 * skipped rows that never became pairs/jobs) and cancel every job the submission created.
 */
@RestController
@RequestMapping("/api/v1/bulk")
@RequiredArgsConstructor
public class BulkMigrationController {

    private final BulkSubmissionRepository bulkSubmissionRepository;
    private final BulkSubmissionService bulkSubmissionService;

    @GetMapping("/{id}")
    public ResponseEntity<BulkSubmission> getSubmission(@PathVariable String id) {
        return bulkSubmissionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<BulkSubmission>> getSubmissions() {
        return ResponseEntity.ok(bulkSubmissionRepository.findAll());
    }

    /** Cancels queued + in-progress jobs of one submission. Skipped rows have no jobs and are untouched. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSubmission(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object reasonObj = body != null ? body.get("reason") : null;
        String reason = reasonObj != null ? reasonObj.toString()
                : "Cancelled by operator (bulk submission)";
        int cancelled = bulkSubmissionService.cancelSubmission(id, reason);
        return ResponseEntity.ok(Map.of(
                "submissionId", id,
                "cancelled", cancelled,
                "status", "success"));
    }
}
