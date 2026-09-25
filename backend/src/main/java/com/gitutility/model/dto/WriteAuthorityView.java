package com.gitutility.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class WriteAuthorityView {
    private List<Pair> pairs;
    private List<OrgRow> orgs;
    private List<EnterpriseRow> enterprises;

    @Data
    @Builder
    public static class Pair {
        private String id;
        private String name;
        private String linkMode;
        private List<Side> sides;
    }

    @Data
    @Builder
    public static class Side {
        private String side;
        private String repoFullName;
        private String repoUrl;
        private String credentialId;
        private String credentialLabel;
        private String access;
        private String scope;
        private String target;
        private String enforcement;
        private Long rulesetId;
        private String rulesetName;
        /** Live GitHub state for the saved scope: missing, active, disabled, or unknown. */
        private String rulesetState;
        private String rulesetDetail;
        /** Repository ruleset gitmirror-replica-readonly, looked up even when the saved scope is org or enterprise. */
        private String repoRulesetName;
        private String repoRulesetState;
        private String repoRulesetDetail;
        private Chip repo;
        private Chip org;
        private Chip enterprise;
        private String coverageNote;
    }

    @Data
    @Builder
    public static class Chip {
        private boolean enabled;
        private String reason;
        private String orgLogin;
    }

    @Data
    @Builder
    public static class OrgRow {
        private String credentialId;
        private String credentialLabel;
        private String orgLogin;
        private String provider;
        private boolean canManage;
        private String disabledReason;
        private String access;
        private String enforcement;
        private Long rulesetId;
        private String rulesetName;
        private String rulesetState;
        private String rulesetDetail;
        private List<RepoRow> repos;
        /** Set when the installation's repository list could not be loaded. */
        private String repoListError;
    }

    @Data
    @Builder
    public static class RepoRow {
        private String credentialId;
        private String credentialLabel;
        private String appId;
        private String fullName;
        private boolean privateRepo;
        private boolean canManage;
        private String disabledReason;
        private Long rulesetId;
        private String rulesetName;
        private String rulesetState;
        private String rulesetDetail;
    }

    @Data
    @Builder
    public static class EnterpriseRow {
        private String credentialId;
        private String credentialLabel;
        private String slug;
        private boolean probeOk;
        private String disabledReason;
        private String access;
        private String enforcement;
        private Long rulesetId;
        private String rulesetName;
        private String rulesetState;
        private String rulesetDetail;
    }
}
