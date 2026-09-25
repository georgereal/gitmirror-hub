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
public class PeerStatusResponse {

    private String mappingId;
    private String phase;
    private String processing;
    private String pausedReason;
    private long parkedCount;
    private String link;
    @Builder.Default
    private List<Side> sides = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Side {
        private String id;
        private String provider;
        private String host;
        private String role;
        private String reachable;
        private Instant checkedAt;
        private String error;
        private String access;
        private String lock;
        private String rulesetRollup;
        @Builder.Default
        private List<RulesetTarget> rulesetTargets = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RulesetTarget {
        private String scope;
        private String name;
        private String state;
        private String error;
    }
}
