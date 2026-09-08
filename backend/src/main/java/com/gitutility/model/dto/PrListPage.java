package com.gitutility.model.dto;

import com.gitutility.model.dto.SyncDiffReport.PrSyncDetail;

import java.util.List;

/** One page of pull requests from a provider (GraphQL cursor or REST Link URL). */
public record PrListPage(
        List<PrSyncDetail> items,
        String nextCursor,
        boolean hasNextPage,
        int totalCount,
        boolean graphql) {

    public PrListPage(List<PrSyncDetail> items, String nextCursor, boolean hasNextPage, int totalCount) {
        this(items, nextCursor, hasNextPage, totalCount, false);
    }

    public static PrListPage empty() {
        return new PrListPage(List.of(), null, false, 0, false);
    }
}
