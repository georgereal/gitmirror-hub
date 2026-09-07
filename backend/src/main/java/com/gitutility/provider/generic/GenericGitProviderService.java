package com.gitutility.provider.generic;

import com.gitutility.model.dto.*;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.PublicReadProbe;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.repository.GitHubAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback adapter for standard Git remotes (Azure DevOps, AWS CodeCommit, self-hosted Gitea, etc.).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenericGitProviderService implements ScmProviderAdapter {

    private final GitHubAppConfigRepository configRepository;

    private static final Pattern GENERIC_REPO_PATTERN =
            Pattern.compile("(?:https?://[^/]+/|git@[^:]+:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    @Override
    public ScmProviderType getProviderType() {
        return ScmProviderType.GENERIC;
    }

    private GitHubAppConfig getConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> GitHubAppConfig.builder().build());
    }

    @Override
    public boolean supportsUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return false;
        String lower = repoUrl.toLowerCase().trim();
        // Do not match known major SCM providers
        if (lower.contains("github.com") || lower.contains("bitbucket.org") || lower.contains("gitlab.com") || lower.contains("origin.cursor.com")) {
            return false;
        }
        return true; // Fallback provider for arbitrary Git remotes (Azure DevOps, AWS CodeCommit, Gitea, etc.)
    }

    @Override
    public String parseRepoFullName(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher m = GENERIC_REPO_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        return null;
    }

    @Override
    public CredentialsProvider getGitCredentials(String repoUrl, String explicitToken) {
        String token = explicitToken;
        String username = "oauth2";

        if (token == null || token.isBlank()) {
            GitHubAppConfig config = getConfig();
            token = config.getGenericAccessToken() != null ? config.getGenericAccessToken() : config.getDefaultPatToken();
            username = config.getGenericUsername() != null ? config.getGenericUsername() : "oauth2";
        }

        if (token == null || token.isBlank()) {
            return null;
        }

        return new UsernamePasswordCredentialsProvider(username, token.trim());
    }

    @Override
    public PermissionCheckReport testConnection(TestConnectionRequest req) {
        String repoFullName = parseRepoFullName(req.getRepoUrl());
        boolean writeRequired = PublicReadProbe.writeRequired(req);
        boolean skipPublic = PublicReadProbe.skipAnonymousProbe(req);
        boolean publicRead = !skipPublic && PublicReadProbe.lsRemoteAnonymous(req.getRepoUrl());

        if (publicRead && !writeRequired) {
            return PublicReadProbe.publicReadSuccess(repoFullName, "main", "Generic Git");
        }

        CredentialsProvider creds = getGitCredentials(req.getRepoUrl(), req.getToken());
        if (creds != null && PublicReadProbe.lsRemote(req.getRepoUrl(), creds)) {
            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch("main")
                    .isPrivate(true)
                    .accessMode("AUTHENTICATED")
                    .message("Authenticated Git remote reachable.")
                    .permissions(PermissionCheckReport.PermissionsDetail.builder()
                            .contentsRead(true)
                            .contentsWrite(true)
                            .build())
                    .passedChecks(List.of("Authenticated git ls-remote succeeded: " + req.getRepoUrl()))
                    .build();
        }

        if (publicRead && writeRequired) {
            return PublicReadProbe.publicReadButWriteNeedsCredentials(repoFullName, "main");
        }

        if (creds == null) {
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(401)
                    .repoFullName(repoFullName)
                    .message("Generic Git remote is not publicly readable. Credentials are required.")
                    .errors(List.of("Anonymous git ls-remote failed. Configure a generic access token in Provider Settings."))
                    .build();
        }

        return PermissionCheckReport.builder()
                .valid(false)
                .httpStatusCode(403)
                .repoFullName(repoFullName)
                .message("Could not reach the Git remote with the provided credentials.")
                .errors(List.of("git ls-remote failed for: " + req.getRepoUrl()))
                .build();
    }

    @Override
    public RepoSearchResult searchRepositories(String query, int page, int perPage) {
        return RepoSearchResult.builder()
                .items(new ArrayList<>())
                .totalCount(0)
                .page(page)
                .perPage(perPage)
                .hasMore(false)
                .provider("generic")
                .build();
    }

    @Override
    public List<GitHubRepoOption> listAccessibleRepositories() {
        return Collections.emptyList();
    }

    @Override
    public boolean createRemoteRepository(CreateRepoRequest req) {
        return false;
    }

    @Override
    public List<SyncDiffReport.PrSyncDetail> listOpenPullRequests(String repoFullName) {
        return Collections.emptyList();
    }

    @Override
    public Long createPullRequest(String repoFullName, String title, String body, String headRef, String baseRef) {
        return null;
    }

    @Override
    public void replicateComments(String repoFullName, long prNumber, List<String> comments) {}

    @Override
    public boolean replicateCommitStatus(String repoFullName, String sha, String state, String targetUrl, String description, String context) {
        return false;
    }

    @Override
    public List<SyncDiffReport.ReleaseDetail> listReleases(String repoFullName) {
        return Collections.emptyList();
    }

    @Override
    public List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha) {
        return Collections.emptyList();
    }
}
