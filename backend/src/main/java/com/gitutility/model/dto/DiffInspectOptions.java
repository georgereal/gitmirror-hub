package com.gitutility.model.dto;

/**
 * Controls how expensive {@code computeSyncDiff} work is.
 * Branch aggregate counts always cover every ref; {@code maxBranchDetails} only caps table rows.
 * Refresh Diff ({@code refresh} / {@code includeMetadata}) inspects the full pair — including
 * every unique branch tip for Git LFS — then persists that result for page load.
 */
public record DiffInspectOptions(
        boolean refresh,
        boolean includeMetadata,
        int maxBranchDetails,
        int branchOffset,
        String branchSearch,
        String branchStatus) {

    /** Default branch table page size on page load (counts still cover every ref). */
    public static final int QUICK_DETAIL_ROWS = 50;
    /** Default branch table page size after an explicit Refresh Diff. */
    public static final int FULL_DETAIL_ROWS = 50;

    public static DiffInspectOptions quick() {
        return new DiffInspectOptions(false, false, QUICK_DETAIL_ROWS, 0, null, null);
    }

    public static DiffInspectOptions full() {
        return new DiffInspectOptions(true, true, FULL_DETAIL_ROWS, 0, null, null);
    }

    public boolean hasBranchPaging() {
        return branchOffset > 0
                || (branchSearch != null && !branchSearch.isBlank())
                || (branchStatus != null && !branchStatus.isBlank() && !"ALL".equalsIgnoreCase(branchStatus));
    }

    public static DiffInspectOptions fromRequest(
            boolean refresh,
            boolean metadata,
            Integer maxBranches,
            Integer branchOffset,
            String branchSearch,
            String branchStatus) {
        int detailCap = maxBranches != null && maxBranches > 0
                ? Math.min(maxBranches, 2000)
                : (refresh || metadata ? FULL_DETAIL_ROWS : QUICK_DETAIL_ROWS);
        int offset = branchOffset != null ? Math.max(0, branchOffset) : 0;
        return new DiffInspectOptions(refresh, metadata, detailCap, offset, branchSearch, branchStatus);
    }

    /** @deprecated use {@link #maxBranchDetails()} */
    @Deprecated
    public int maxBranches() {
        return maxBranchDetails;
    }
}
