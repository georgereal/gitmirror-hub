package com.gitutility.model.dto;

import com.gitutility.model.dto.SyncDiffReport.ReleaseDetail;

import java.util.List;

/**
 * One page of releases from a provider.
 * {@code nextCursor} is provider-specific (GraphQL endCursor or REST page marker);
 * {@code null} {@code nextCursor} with {@code hasNextPage=false} ends the walk.
 */
public record ReleaseListPage(
        List<ReleaseDetail> items,
        String nextCursor,
        boolean hasNextPage,
        long totalCount,
        boolean graphql) {

    public static ReleaseListPage empty() {
        return new ReleaseListPage(List.of(), null, false, 0, false);
    }
}