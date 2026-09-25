package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-row outcomes of a bulk migration submission. Rows marked {@link Outcome#SKIPPED}
 * never became pairs or jobs; rows marked {@link Outcome#CREATED_QUEUED} have a mapping
 * and a QUEUED bootstrap job in {@code git.sync.queue} (or the in-process queue).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkMirrorResponse {

    private String submissionId;

    private List<Row> rows;
    private int createdQueuedCount;
    private int skippedCount;
    private int failedValidationCount;

    public static BulkMirrorResponse of(String submissionId, List<Row> rows) {
        List<Row> safe = rows != null ? rows : List.of();
        return BulkMirrorResponse.builder()
                .submissionId(submissionId)
                .rows(safe)
                .createdQueuedCount((int) safe.stream().filter(r -> r.getOutcome() == Outcome.CREATED_QUEUED).count())
                .skippedCount((int) safe.stream().filter(r -> r.getOutcome() == Outcome.SKIPPED).count())
                .failedValidationCount((int) safe.stream().filter(r -> r.getOutcome() == Outcome.FAILED_VALIDATION).count())
                .build();
    }

    public enum Outcome { CREATED_QUEUED, SKIPPED, FAILED_VALIDATION }

    @lombok.Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String sourceUrl;
        private String destUrl;
        private Outcome outcome;
        private String reason;
        private String mappingId;
        private String jobId;
    }
}
