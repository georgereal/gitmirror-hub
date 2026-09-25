package com.gitutility.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class WriteAuthorityRequest {
    /** {@code linked} or {@code independent}. */
    private String link;
    private String pairId;
    private List<Side> sides;
    private OrgRow org;
    private RepositoryRow repository;
    private EnterpriseRow enterprise;
    /** Required text {@code ALL REPOS} when a read-only placement targets every repository. */
    private String confirmAllRepos;

    @Data
    public static class Side {
        private String side;
        /** {@code write} or {@code readonly}. */
        private String access;
        /** {@code repo}, {@code org}, or {@code enterprise}. */
        private String scope;
        /** {@code this_repo} or {@code all_repos}. */
        private String target;
    }

    @Data
    public static class OrgRow {
        private String credentialId;
        private String orgLogin;
        private String access;
    }

    @Data
    public static class RepositoryRow {
        private String credentialId;
        private String repoFullName;
        /** Installation that listed this repository. */
        private String installationId;
        /** {@code write} or {@code readonly}. */
        private String access;
    }

    @Data
    public static class EnterpriseRow {
        private String credentialId;
        private String access;
    }

    @Data
    public static class RulesetEnforcement {
        private String credentialId;
        private String repoFullName;
        private String installationId;
        private long rulesetId;
        /** {@code active} or {@code disabled}. */
        private String enforcement;
    }
}
