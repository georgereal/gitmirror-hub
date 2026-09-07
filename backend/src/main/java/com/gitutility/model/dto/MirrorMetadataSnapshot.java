package com.gitutility.model.dto;

import com.gitutility.model.dto.SyncDiffReport.PrSyncDetail;
import com.gitutility.model.dto.SyncDiffReport.ReleaseDetail;

import java.util.List;

/** One-shot SCM metadata for Refresh Diff: open PR total + preview page + recent releases. */
public record MirrorMetadataSnapshot(
        int openPrTotalCount,
        List<PrSyncDetail> prPreview,
        boolean pullRequestsTruncated,
        int releaseTotalCount,
        List<ReleaseDetail> releases) {

    public static MirrorMetadataSnapshot empty() {
        return new MirrorMetadataSnapshot(0, List.of(), false, 0, List.of());
    }
}
