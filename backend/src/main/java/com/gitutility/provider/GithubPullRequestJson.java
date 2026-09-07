package com.gitutility.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitutility.model.dto.SyncDiffReport;

public final class GithubPullRequestJson {

    private GithubPullRequestJson() {
    }

    public static SyncDiffReport.PrSyncDetail toPrSyncDetail(JsonNode pr, String repoFullName) {
        String headRepo = pr.path("head").path("repo").path("full_name").asText(null);
        boolean isFork = headRepo != null && !headRepo.equalsIgnoreCase(repoFullName);
        return SyncDiffReport.PrSyncDetail.builder()
                .sourcePrNumber(pr.path("number").asLong())
                .title(pr.path("title").asText())
                .headBranch(pr.path("head").path("ref").asText())
                .baseBranch(pr.path("base").path("ref").asText())
                .state("open")
                .isFork(isFork)
                .authorLogin(pr.path("user").path("login").asText(null))
                .sourcePrUrl(pr.path("html_url").asText(null))
                .body(pr.path("body").asText(null))
                .commentsCount(pr.path("comments").asInt(0))
                .reviewCommentsCount(pr.path("review_comments").asInt(0))
                .draft(pr.path("draft").asBoolean(false))
                .build();
    }
}
