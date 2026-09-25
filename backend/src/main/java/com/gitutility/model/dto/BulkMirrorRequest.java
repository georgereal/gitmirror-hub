package com.gitutility.model.dto;

import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.model.enums.StorageTier;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.TrunkConflictPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bulk migration submission from the Add Repo Pair modal (Bulk migration tab).
 * Creates one pair + one bootstrap job per accepted row; skipped rows are recorded
 * on the {@code bulk_submissions} record with a reason.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkMirrorRequest {

    /** CREATE_DEST (Option 1 — create destination repos at job start) | USE_EXISTING (Option 2). */
    private String mode;

    private List<BulkItem> items;

    /** Shared pair parameters applied to every accepted row. */
    private String branchPattern;
    private SyncDirection syncDirection;
    private TrunkConflictPolicy trunkConflictPolicy;
    private StorageTier storageTier;
    private Boolean active;

    /** Option 1 only: destination owner/org, credential and visibility for created repos. */
    private String destOwner;
    private String destCredentialId;
    private String destInstallationId;
    private Boolean destPrivate;
    /** Option 1 / selective create: PUBLIC, PRIVATE, or INTERNAL. Wins over {@link #destPrivate}. */
    private RepoVisibility destVisibility;

    /** Soft guard: bounds the synchronous HTTP request (probing), NOT concurrent execution. */
    public static final int DEFAULT_MAX_ITEMS = 1000;
    /** Submission-time probes process items in chunks of this size. */
    public static final int PROBE_CHUNK_SIZE = 250;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkItem {
        private String sourceUrl;
        private String sourceProvider;
        private String sourceCredentialId;
        private String sourceInstallationId;
        private RepoVisibility sourceVisibility;
        private Boolean sourcePublicRead;

        /** Option 1: destination repo name (defaults to source name); null for Option 2. */
        private String destName;

        /** Option 2: existing destination clone URL; null for Option 1. */
        private String destUrl;
        private String destCredentialId;
        private String destInstallationId;
        /** Visibility of an existing destination, when the picker reported it. */
        private RepoVisibility destVisibility;

        /** Option 2 only: operator's decision on a destination that already has commits. */
        private Boolean includeNonEmptyDest;
    }
}
