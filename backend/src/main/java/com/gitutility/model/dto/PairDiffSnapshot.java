package com.gitutility.model.dto;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted pair-level sync summary for repo detail cards (survives page refresh).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairDiffSnapshot {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private Instant capturedAt;
    /** FULL_REFRESH, SYNC_GIT, SYNC_PR, SYNC_RELEASE */
    private String source;

    private String overallStatus;

    private int totalBranchesCount;
    private int sourceBranchesCount;
    private int destBranchesCount;
    private int inSyncBranchesCount;
    private int pendingBranchesCount;
    private int divergedBranchesCount;
    private int destOnlyBranchesCount;

    private int prsTotal;
    private int prsSynced;
    private int prsDestCount;

    private int lfsTotal;
    private int lfsSynced;
    private int lfsPending;

    private int tagsSourceCount;
    private int tagsTargetCount;

    private int releasesSourceCount;
    private int releasesTargetCount;

    public static PairDiffSnapshot parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, PairDiffSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }
}
