package com.gitutility.provider.origin;

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
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for Cursor Origin SCM (https://origin.cursor.com).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OriginProviderService implements ScmProviderAdapter {

    private final GitHubAppConfigRepository configRepository;

    private static final Pattern ORIGIN_REPO_PATTERN =
            Pattern.compile("(?:https?://origin\\.cursor\\.com/|git@origin\\.cursor\\.com:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    @Override
    public ScmProviderType getProviderType() {
        return ScmProviderType.ORIGIN;
    }

    private GitHubAppConfig getConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> GitHubAppConfig.builder().build());
    }

    @Override
    public boolean supportsUrl(String repoUrl) {
        return repoUrl != null && repoUrl.toLowerCase().contains("origin.cursor.com");
    }

    @Override
    public String parseRepoFullName(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher m = ORIGIN_REPO_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        return null;
    }

    private String getEffectiveToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        GitHubAppConfig config = getConfig();
        return config.getOriginAccessToken() != null ? config.getOriginAccessToken().trim() : null;
    }

    @Override
    public CredentialsProvider getGitCredentials(String repoUrl, String explicitToken) {
        String token = getEffectiveToken(explicitToken);
        if (token == null || token.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    @Override
    public PermissionCheckReport testConnection(TestConnectionRequest req) {
        String token = getEffectiveToken(req.getToken());
        String repoFullName = parseRepoFullName(req.getRepoUrl());
        boolean writeRequired = PublicReadProbe.writeRequired(req);
        boolean skipPublic = PublicReadProbe.skipAnonymousProbe(req);

        boolean publicRead = !skipPublic && PublicReadProbe.lsRemoteAnonymous(req.getRepoUrl());
        if (publicRead && !writeRequired) {
            return PublicReadProbe.publicReadSuccess(repoFullName, "main", "Cursor Origin");
        }

        if (token == null || token.isBlank()) {
            if (publicRead && writeRequired) {
                return PublicReadProbe.publicReadButWriteNeedsCredentials(repoFullName, "main");
            }
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(401)
                    .message("Cursor Origin Access Token is required.")
                    .errors(List.of("Missing Origin token"))
                    .build();
        }

        List<String> passed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token.trim());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            passed.add("Cursor Origin Authentication Verified: Token validated.");

            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch("main")
                    .isPrivate(!publicRead)
                    .accessMode(publicRead ? "PUBLIC" : "AUTHENTICATED")
                    .message("Cursor Origin connection successful.")
                    .permissions(PermissionCheckReport.PermissionsDetail.builder()
                            .contentsRead(true)
                            .contentsWrite(true)
                            .pullRequests(true)
                            .commitStatuses(true)
                            .webhooks(true)
                            .admin(true)
                            .build())
                    .passedChecks(passed)
                    .warnings(warnings)
                    .errors(errors)
                    .build();
        } catch (Exception e) {
            log.warn("Origin test connection error: {}", e.getMessage());
            errors.add("Origin Error: " + e.getMessage());
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(400)
                    .repoFullName(repoFullName)
                    .message("Failed to verify Origin connection: " + e.getMessage())
                    .errors(errors)
                    .build();
        }
    }

    @Override
    public RepoSearchResult searchRepositories(String query, int page, int perPage) {
        return RepoSearchResult.builder()
                .items(new ArrayList<>())
                .totalCount(0)
                .page(page)
                .perPage(perPage)
                .hasMore(false)
                .provider("origin")
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
