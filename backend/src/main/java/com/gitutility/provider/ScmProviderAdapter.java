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
     * Lists CI check runs and commit status executions for a given commit.
     */
    List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha);

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
}
