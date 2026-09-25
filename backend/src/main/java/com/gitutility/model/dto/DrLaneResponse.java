package com.gitutility.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class DrLaneResponse {
    private String laneKey;
    private String sourceProvider;
    private String sourceHost;
    private String targetProvider;
    private String targetHost;
    private String phase;
    private String processing;
    private String link;
    private String lockScope;
    private String sourceReachable;
    private String targetReachable;
    private String sourceRole;
    private String targetRole;
    private String flow;
    /** green, yellow, red, or none when no ruleset has been written. */
    private String rulesetRollup;
    private long parkedCount;
    private int pairCount;
    @Builder.Default
    private List<ScopeRow> orgs = new ArrayList<>();
    @Builder.Default
    private List<ScopeRow> enterprises = new ArrayList<>();
    @Builder.Default
    private List<PairRow> pairs = new ArrayList<>();
    @Builder.Default
    private List<RulesetRow> rulesets = new ArrayList<>();

    @Data
    @Builder
    public static class ScopeRow {
        private String side;
        private String name;
        private String credentialId;
        private String reachable;
        private String rulesetRollup;
        private int pairCount;
    }

    @Data
    @Builder
    public static class RulesetRow {
        /** enterprise, org, or repo. */
        private String scope;
        /** A is the source provider, B is the destination. */
        private String side;
        private String name;
        /** applied, pending, open, or none. */
        private String state;
        private String detail;
        private int pairCount;
    }

    @Data
    @Builder
    public static class PairRow {
        private String id;
        private String name;
        private String phase;
        private String reachableA;
        private String reachableB;
        private String lock;
        private String rulesetRollupA;
        private String rulesetRollupB;
    }
}
