package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmQuotaResponse {

    private Instant capturedAt;
    @Builder.Default
    private List<InstallQuota> installations = new ArrayList<>();
    @Builder.Default
    private List<RepoHotspot> hottestRepos = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstallQuota {
        private String provider;
        private String installationKey;
        private Integer restRemaining;
        private Integer restLimit;
        private Instant restResetAt;
        private long restCalls;
        private long rest429Count;
        private Integer graphqlRemaining;
        private Integer graphqlLimit;
        private long graphqlCalls;
        private long graphqlPointsUsed;
        private long graphql429Count;
        private Instant updatedAt;
        @Builder.Default
        private List<QuotaSample> series = new ArrayList<>();
        private long githubRestConsumed;
        private long appRestConsumed;
        private long externalRestSuspect;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuotaSample {
        private Instant at;
        private Integer restRemaining;
        private Integer restLimit;
        private long appRestCalls;
        private Integer graphqlRemaining;
        private long appGraphqlCalls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepoHotspot {
        private String provider;
        private String repoFullName;
        private long gitFetches;
        private long gitPushes;
        private long gitThrottles;
        private long graphqlCalls;
        private long graphqlPointsUsed;
        private long graphql429Count;
        private long heatScore;
        private Instant updatedAt;
    }
}
