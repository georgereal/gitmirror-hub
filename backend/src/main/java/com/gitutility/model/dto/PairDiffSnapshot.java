package com.gitutility.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
