package com.gitutility.provider;

/**
 * One read-only ruleset placement: a repository, an organization, or a GitHub Enterprise Cloud enterprise.
 * {@code appId} is the GitHub App id used as the bypass actor.
 */
public record ReadonlyRulesetSpec(
        String kind,
        String target,
        String repoFullName,
        String orgLogin,
        String enterpriseSlug,
        long appId,
        String enforcement) {

    public static final String KIND_REPO = "repo";
    public static final String KIND_ORG = "org";
    public static final String KIND_ENTERPRISE = "enterprise";
    public static final String TARGET_THIS_REPO = "this_repo";
    public static final String TARGET_ALL_REPOS = "all_repos";

    public String repoShortName() {
        if (repoFullName == null || repoFullName.isBlank()) {
            return "";
        }
        int slash = repoFullName.indexOf('/');
        return slash < 0 ? repoFullName : repoFullName.substring(slash + 1);
    }

    public String rulesetName() {
        return GitHubRulesetClient.rulesetName(kind, target, orgLogin, repoShortName());
    }
}
