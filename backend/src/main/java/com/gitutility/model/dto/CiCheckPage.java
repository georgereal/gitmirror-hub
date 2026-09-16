package com.gitutility.model.dto;

import com.gitutility.model.dto.SyncDiffReport.CiCheckRunDetail;

import java.util.List;

/** One page of CI check runs for a commit (REST page-param cursor). */
public record CiCheckPage(
        List<CiCheckRunDetail> items,
        int nextPage,
        boolean hasNextPage,
        long totalCount) {

    public static CiCheckPage empty() {
        return new CiCheckPage(List.of(), 0, false, 0);
    }
}