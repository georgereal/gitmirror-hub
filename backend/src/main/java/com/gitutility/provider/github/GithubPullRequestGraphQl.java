package com.gitutility.provider.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitutility.model.dto.PrListPage;
import com.gitutility.model.dto.SyncDiffReport;

import java.util.ArrayList;
import java.util.List;

public final class GithubPullRequestGraphQl {

    private GithubPullRequestGraphQl() {
    }

    public static PrListPage parseOpenPullRequestsPage(JsonNode data, String repoFullName) {
        if (data == null || data.isMissingNode()) {
            return PrListPage.empty();
        }
        JsonNode pullRequests = data.path("repository").path("pullRequests");
        if (pullRequests.isMissingNode()) {
            return PrListPage.empty();
        }
        int totalCount = pullRequests.path("totalCount").asInt(0);
        JsonNode pageInfo = pullRequests.path("pageInfo");
        boolean hasNext = pageInfo.path("hasNextPage").asBoolean(false);
        String endCursor = pageInfo.path("endCursor").asText(null);

        List<SyncDiffReport.PrSyncDetail> items = new ArrayList<>();
        JsonNode nodes = pullRequests.path("nodes");
        if (nodes.isArray()) {
            for (JsonNode pr : nodes) {
                if (pr == null || pr.isNull()) {
                    continue;
                }
                String headRepo = pr.path("headRepository").path("nameWithOwner").asText(null);
                boolean isFork = pr.path("isCrossRepository").asBoolean(false)
                        || (headRepo != null && !headRepo.equalsIgnoreCase(repoFullName));
                items.add(SyncDiffReport.PrSyncDetail.builder()
                        .sourcePrNumber(pr.path("number").asLong())
                        .title(pr.path("title").asText(""))
                        .headBranch(pr.path("headRefName").asText())
                        .baseBranch(pr.path("baseRefName").asText())
                        .state("open")
                        .isFork(isFork)
                        .authorLogin(pr.path("author").path("login").asText(null))
                        .sourcePrUrl(pr.path("url").asText(null))
                        .body(pr.path("body").asText(null))
                        .commentsCount(pr.path("comments").path("totalCount").asInt(0))
                        .reviewCommentsCount(pr.path("reviewThreads").path("totalCount").asInt(0))
                        .draft(pr.path("isDraft").asBoolean(false))
                        .build());
            }
        }
        return new PrListPage(items, hasNext ? endCursor : null, hasNext, totalCount);
    }

    public static int parseOpenPullRequestTotal(JsonNode data) {
        if (data == null) {
            return 0;
        }
        return data.path("repository").path("pullRequests").path("totalCount").asInt(0);
    }
}
