package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SyncDiffReport {
    private Long mappingId;
    private String pairName;
    private String sourceRepo;
    private String targetRepo;
    private String overallStatus; // IN_SYNC, PENDING_SYNC, DIVERGED, UNKNOWN
    private int totalBranchesCount;
    /** Distinct branch heads on the source remote. */
    private int sourceBranchesCount;
    /** Distinct branch heads on the destination remote. */
    private int destBranchesCount;
    private int inSyncBranchesCount;
    private int pendingBranchesCount;
    private int divergedBranchesCount;
    /** Refs present on destination but absent on source — not actionable for source→dest mirror. */
    private int destOnlyBranchesCount;

    @Builder.Default
    private List<BranchDiffDetail> branches = new ArrayList<>();

    @Builder.Default
    private List<PrSyncDetail> pullRequests = new ArrayList<>();

    private LfsSyncSummary lfs;
    private TagSyncSummary tags;
    private ReleaseSyncSummary releases;
    private CiStatusSummary ciStatuses;

    @Builder.Default
    private List<TagDetail> tagItems = new ArrayList<>();

    @Builder.Default
    private List<ReleaseDetail> releaseItems = new ArrayList<>();

    @Builder.Default
    private List<LfsPointerDetail> lfsItems = new ArrayList<>();

    @Builder.Default
    private List<CiCheckRunDetail> ciCheckRuns = new ArrayList<>();

    /** Non-null when inspection partially failed; UI can surface without hanging. */
    private String inspectionError;
    private String inspectionMode;
    private boolean branchesTruncated;
    /** Rows skipped in the current branch table page (after filters). */
    private int branchesPageOffset;
    /** Rows returned in the current branch table page. */
    private int branchesPageSize;
    /** Branch rows matching the current search/status filter (before paging). */
    private int branchesFilteredCount;
    private boolean metadataDeferred;
    /** When card metrics were loaded from persisted DB snapshot (quick page load). */
    private boolean fromPersistedSnapshot;
    private Instant persistedSnapshotAt;
    private String persistedSnapshotSource;
    private Integer persistedPrsTotal;
    private Integer persistedPrsSynced;
    /** Total open PRs on source when preview list is truncated. */
    private int totalOpenPrsCount;
    private boolean pullRequestsTruncated;
    /** Mirrored PR count from pair mapping ledger (accurate when preview is truncated). */
    private int mirroredPrsCount;
    /** Open PRs on destination that this pair has mapped (dest-side mirrored count). */
    private int destOpenPrsCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BranchDiffDetail {
        private String branchName;
        private String sourceSha;
        private String sourceShortSha;
        private String targetSha;
        private String targetShortSha;
        private String status; // IN_SYNC, AHEAD, BEHIND, DIVERGED, TARGET_MISSING, SOURCE_MISSING
        private int aheadCount;
        private int behindCount;
        private String lastCommitMessage;
        private String lastCommitAuthor;
        private boolean forkPrHead;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrSyncDetail {
        private Long sourcePrNumber;
        private Long targetPrNumber;
        private String title;
        private String state;
        private String headBranch;
        private String baseBranch;
        @JsonProperty("isSynced")
        private boolean isSynced;
        private String syncStatus; // MIRRORED, PENDING, SKIPPED, FAILED
        private String reason;
        @JsonProperty("isFork")
        private boolean isFork;
        /** Source PR author login (e.g. GitHub @handle). */
        private String authorLogin;
        /** Canonical URL of the source pull request. */
        private String sourcePrUrl;
        /** Source PR description (for mirror body). */
        private String body;
        private int commentsCount;
        private int reviewCommentsCount;
        @JsonProperty("isDraft")
        private boolean draft;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LfsSyncSummary {
        private int totalDiscovered;
        private int syncedCount;
        private int pendingCount;
        private boolean inSync;
        /** False when destination LFS batch verify did not complete; dest count should not be treated as 0. */
        private boolean destVerified;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagSyncSummary {
        private int sourceTagsCount;
        private int targetTagsCount;
        private boolean inSync;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseSyncSummary {
        private int sourceReleasesCount;
        private int targetReleasesCount;
        private boolean inSync;
        private String latestReleaseTag;
        private int totalAssetsCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CiStatusSummary {
        private int replicatedStatusesCount;
        private boolean inSync;
        private String latestStatusState;
        private String latestContext;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagDetail {
        private String tagName;
        private String refName;
        private String targetSha;
        private String targetShortSha;
        private boolean isAnnotated;
        private String message;
        private String taggerName;
        private String taggerDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseAssetDetail {
        private Long id;
        private String name;
        private long sizeBytes;
        private String formattedSize;
        private String downloadUrl;
        private String contentType;
        private int downloadCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseDetail {
        private Long id;
        private String name;
        private String tagName;
        private String body;
        private String publishedAt;
        private String author;
        private boolean isDraft;
        private boolean isPrerelease;
        private String htmlUrl;
        @Builder.Default
        private List<ReleaseAssetDetail> assets = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LfsPointerDetail {
        private String filePath;
        private String oid;
        private String shortOid;
        private long sizeBytes;
        private String formattedSize;
        private String headBranch;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CiCheckRunDetail {
        private Long id;
        private String name;
        private String status; // completed, in_progress, queued
        private String conclusion; // success, failure, neutral, cancelled, timed_out, action_required
        private String startedAt;
        private String completedAt;
        private String htmlUrl;
        private String appName;
        private String headSha;
    }
}
