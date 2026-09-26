package com.gitutility.provider;

import com.gitutility.model.dto.*;
import com.gitutility.model.enums.ScmProviderType;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.util.List;

/**
 * Standardized contract implemented by all SCM provider integration services
 * (GitHub Cloud, GitHub Enterprise Server, Bitbucket Cloud, GitLab, Origin, Generic).
 */
public interface ScmProviderAdapter {

    /**
     * The unique SCM provider type handled by this adapter.
     */
    ScmProviderType getProviderType();

    /**
     * Checks if this adapter handles the given repository URL.
     */
    boolean supportsUrl(String repoUrl);

    /**
     * Extracts normalized full repository name (e.g., "owner/repo" or "workspace/repo_slug") from URL.
     */
    String parseRepoFullName(String repoUrl);

    /**
     * Resolves JGit credentials provider for remote fetch/push operations.
     */
    CredentialsProvider getGitCredentials(String repoUrl, String explicitToken);

    /**
     * Validates credentials and repository permissions against SCM provider REST API.
     */
    PermissionCheckReport testConnection(TestConnectionRequest req);

    /**
     * Searches remote repositories with pagination and query filtering.
     */
    RepoSearchResult searchRepositories(String query, int page, int perPage);

    /**
     * Lists accessible repositories under the configured account or workspace.
     */
    List<GitHubRepoOption> listAccessibleRepositories();

    /**
     * Auto-creates a new destination repository on the remote SCM provider.
     */
    boolean createRemoteRepository(CreateRepoRequest req);

    /**
     * Cheap existence probe for a repository URL (used by bulk-migration preflight and by
     * the sync engine's create-destination stage). 404 ⇒ false; 200 ⇒ true; errors ⇒ false
     * (ambiguity is logged by the caller — job-time creation is idempotent either way).
     */
    default boolean repositoryExists(String repoUrl) {
        return false;
    }

    /**
     * Lightweight "destination has content" probe (bulk migration Option 2): whether the
     * default branch has any commits. Unsupported providers return false (no warning).
     */
    default boolean hasCommits(String repoUrl) {
        return false;
    }

    /**
     * Fetches open pull requests from the remote repository.
     */
    List<SyncDiffReport.PrSyncDetail> listOpenPullRequests(String repoFullName);

    /**
     * One page of open pull requests. {@code cursor} is provider-specific (GraphQL endCursor or REST Link URL).
     * Default loads the full list in one shot for providers without native paging.
     */
    default PrListPage listOpenPullRequestsPage(String repoFullName, String cursor, int pageSize) {
        List<SyncDiffReport.PrSyncDetail> all = listOpenPullRequests(repoFullName);
        return new PrListPage(all, null, false, all.size(), false);
    }

    /**
     * One page of recently updated closed/merged PRs (GraphQL or REST). Default empty.
     */
    default PrListPage listRecentlyClosedPullRequestsPage(String repoFullName, String cursor, int pageSize) {
        return PrListPage.empty();
    }

    /**
     * Creates a new Pull Request / Merge Request on the target repository.
     */
    Long createPullRequest(String repoFullName, String title, String body, String headRef, String baseRef);

    /**
     * Closes (or declines) a Pull Request / Merge Request on the repository.
     * Default is a no-op for providers without a close API.
     */
    default void closePullRequest(String repoFullName, long prNumber) {
        // Default no-op
    }

    /**
     * Updates title and/or body of an existing Pull Request / Merge Request.
     * Default is unsupported.
     */
    default boolean updatePullRequest(String repoFullName, long prNumber, String title, String body) {
        return false;
    }

    /**
     * Reads the current title/body of a Pull Request for compare-and-swap metadata sync.
     * Default returns null (CAS falls back to applying the origin edit).
     */
    default PullRequestSnapshot getPullRequest(String repoFullName, long prNumber) {
        return null;
    }

    /**
     * Replicates PR comments from source to target PR.
     */
    void replicateComments(String repoFullName, long prNumber, List<String> comments);

    /**
     * Replicates a commit CI/CD build status across to the target repository.
     */
    boolean replicateCommitStatus(String repoFullName, String sha, String state, String targetUrl, String description, String context);

    /**
     * Lists releases and binary release assets from the repository.
     */
    List<SyncDiffReport.ReleaseDetail> listReleases(String repoFullName);

    /**
     * One cursor-paged page of releases. {@code cursor} is provider-specific (GraphQL
     * endCursor or REST page marker). Default loads the full list in one shot.
     */
    default ReleaseListPage listReleasesPage(String repoFullName, String cursor, int pageSize) {
        List<SyncDiffReport.ReleaseDetail> all = listReleases(repoFullName);
        return new ReleaseListPage(all, null, false, all.size(), false);
    }

    /**
     * Whether this provider supports creating/updating mirror releases on a destination.
     * Providers that only expose tags (Bitbucket) return false and release sync degrades to a notice.
     */
    default boolean supportsReleaseSync() {
        return false;
    }

    /**
     * Finds an existing release on the repository by tag for idempotent mirror diffs.
     * Default reports the tag as missing.
     */
    default ReleaseLookup findReleaseByTag(String repoFullName, String tagName) {
        return ReleaseLookup.missing();
    }

    /**
     * Creates a release on the repository. Returns the provider release externalId
     * (GitHub numeric id, GitLab tag name, ...) or {@code null} on failure.
     */
    default String createRelease(String repoFullName,
                                 String tagName,
                                 String name,
                                 String body,
                                 boolean draft,
                                 boolean prerelease) {
        return null;
    }

    /**
     * Updates an existing release identified by {@code externalId} (from {@link #findReleaseByTag}).
     * Returns true when the mutation was applied.
     */
    default boolean updateRelease(String repoFullName,
                                  String externalId,
                                  String tagName,
                                  String name,
                                  String body,
                                  boolean draft,
                                  boolean prerelease) {
        return false;
    }

    /**
     * Deletes a release by provider id (GitHub numeric id, GitLab tag name).
     * Returns true when the release is gone, including when it was already absent.
     */
    default boolean deleteRelease(String repoFullName, String externalId) {
        return false;
    }

    /**
     * Uploads one binary asset to a release from a local file. Returns true when uploaded.
     * Implementations that cannot host binaries (GitLab links-only, Bitbucket) return false.
     */
    default boolean uploadReleaseAsset(String repoFullName,
                                       String releaseExternalId,
                                       String tagName,
                                       String assetName,
                                       String contentType,
                                       java.io.File file) {
        return false;
    }

    /**
     * Streams a release asset from this repository to a local temp file (authenticated when
     * the repository is private). Returns the file, or {@code null} when the asset cannot be fetched.
     */
    default java.io.File downloadReleaseAsset(String repoFullName,
                                              String assetDownloadUrl,
                                              String assetName) {
        return null;
    }

    /**
     * Lists CI check runs and commit status executions for a given commit.
     */
    List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha);

    /**
     * One REST page of check runs for a commit. {@code cursor} is the 1-based next page
     * number (0 starts at page 1). Default loads the single-shot list.
     */
    default CiCheckPage listCiCheckRunsPage(String repoFullName, String commitSha, int cursor, int pageSize) {
        List<SyncDiffReport.CiCheckRunDetail> all = listCiCheckRuns(repoFullName, commitSha);
        return new CiCheckPage(all, 0, false, all.size());
    }

    /**
     * Lists legacy commit statuses on a commit, newest first.
     */
    default List<CommitStatusDetail> listCommitStatuses(String repoFullName, String commitSha) {
        return List.of();
    }

    /**
     * Whether this provider can create native check runs on a destination commit
     * (GitHub Checks API). When false, check sync degrades to commit statuses.
     */
    default boolean supportsCheckRunSync() {
        return false;
    }

    /**
     * Creates a check run on a destination commit mirroring a source check run.
     * Returns the created check run id, or 0 when unsupported/failed.
     */
    default long createCheckRun(String repoFullName,
                                String commitSha,
                                String name,
                                String status,
                                String conclusion,
                                String startedAt,
                                String completedAt,
                                String detailsUrl,
                                String summary) {
        return 0L;
    }

    /**
     * One GraphQL round-trip (when supported): open PR total + preview rows + recent releases.
     * Default falls back to separate {@link #listOpenPullRequests} / {@link #listReleases} calls.
     */
    default MirrorMetadataSnapshot fetchMirrorMetadataSnapshot(String repoFullName,
                                                               int prPreviewLimit,
                                                               int releaseLimit) {
        List<SyncDiffReport.PrSyncDetail> allPrs = listOpenPullRequests(repoFullName);
        int previewCap = Math.max(0, prPreviewLimit);
        List<SyncDiffReport.PrSyncDetail> preview = allPrs.size() <= previewCap
                ? allPrs
                : allPrs.subList(0, previewCap);
        List<SyncDiffReport.ReleaseDetail> releases = listReleases(repoFullName);
        return new MirrorMetadataSnapshot(
                allPrs.size(),
                preview,
                allPrs.size() > previewCap,
                releases.size(),
                releases);
    }

    /**
     * Cancels in-flight Actions / CI workflow runs attributed to {@code actorLogin} created at or after
     * {@code createdSince}. Returns how many cancel requests were issued. Default is unsupported (0).
     */
    default int cancelWorkflowRunsByActor(String repoFullName, String actorLogin, java.time.Instant createdSince) {
        return 0;
    }

    /**
     * Invalidate any in-memory cached authentication tokens (e.g. GitHub App installation tokens, Bitbucket OAuth tokens).
     */
    default void invalidateTokenCache() {
        // Default no-op
    }

    /**
     * Creates or updates {@code gitmirror-replica-readonly} on a GitHub or GHES repository.
     * {@code appId} is the GitHub App id used as the ruleset bypass actor.
     * {@code enforcement} is {@code active} or {@code disabled}. Returns the ruleset id, or 0 when
     * unlocking a repo that has no ruleset yet.
     */
    default long ensureReplicaReadonlyRuleset(String repoFullName, long appId, String enforcement) {
        throw new UnsupportedOperationException(
                "Repository rulesets are available on GitHub and GitHub Enterprise Server only");
    }

    /**
     * Creates or updates a read-only ruleset at repository, organization, or enterprise scope.
     * {@code enforcement} on the spec is {@code active} or {@code disabled}.
     */
    default long ensureReadonlyRuleset(ReadonlyRulesetSpec spec) {
        throw new UnsupportedOperationException(
                "Repository rulesets are available on GitHub and GitHub Enterprise Server only");
    }

    /** Lists every ruleset on one repository. Does not create or rewrite rules. */
    default java.util.List<GitHubRulesetClient.ListedRuleset> listRepositoryRulesets(String repoFullName) {
        throw new UnsupportedOperationException(
                "Repository rulesets are available on GitHub and GitHub Enterprise Server only");
    }

    /** Changes enforcement on an existing ruleset and leaves its rules in place. */
    default void setRepositoryRulesetEnforcement(String repoFullName, long rulesetId, String enforcement) {
        throw new UnsupportedOperationException(
                "Repository rulesets are available on GitHub and GitHub Enterprise Server only");
    }

    /**
     * Reads whether the ruleset named by {@code spec} exists and how GitHub is enforcing it.
     */
    default RulesetPresence lookupReadonlyRuleset(ReadonlyRulesetSpec spec) {
        String name = spec == null ? null : spec.rulesetName();
        return RulesetPresence.unknown(name,
                "Repository rulesets are available on GitHub and GitHub Enterprise Server only");
    }

    /**
     * @return null when this token can list enterprise rulesets; otherwise the reason it cannot.
     */
    default String probeEnterpriseRulesets(String enterpriseSlug) {
        return "Enterprise rulesets are a GitHub.com API.";
    }
}
