package com.gitutility.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One bulk migration submission from the Add Repo Pair modal (Bulk migration tab).
 * Persists per-row outcomes — including rows skipped before any pair/job existed —
 * so the post-submit summary and the Queue Manager bulk panel stay queryable,
 * and gives all created jobs a common handle for bulk cancel.
 */
@Entity
@Table(name = "bulk_submissions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CREATE_DEST (Option 1) | USE_EXISTING (Option 2). */
    @Column(nullable = false, length = 30)
    private String mode;

    @Column(nullable = false)
    private Integer itemCount;

    @Column(nullable = false)
    @Builder.Default
    private Integer createdCount = 0;

    /** Per-row outcomes for rows that did NOT become pairs/jobs (reason + url). JSON. */
    @Column(columnDefinition = "CLOB")
    private String skippedJson;

    private Instant cancelledAt;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
