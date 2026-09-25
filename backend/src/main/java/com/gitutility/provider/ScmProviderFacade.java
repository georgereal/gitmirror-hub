package com.gitutility.provider;

import com.gitutility.model.dto.*;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.service.FeatureFlagsService;
import com.gitutility.service.ScmCredentialContext;
import com.gitutility.service.ScmCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade router and registry for all SCM Provider Adapters.
 * Provides a unified entry point for Git engines, PR synchronizers, status forwarders,
 * and repository searchers to interact with any SCM provider without vendor lock-in.
 */
@Service
@Slf4j
public class ScmProviderFacade {

    private final List<ScmProviderAdapter> adapters;
    private final Map<ScmProviderType, ScmProviderAdapter> adapterByType = new ConcurrentHashMap<>();
    private final FeatureFlagsService featureFlagsService;
    private final ScmCredentialService scmCredentialService;

    public ScmProviderFacade(List<ScmProviderAdapter> adapters, FeatureFlagsService featureFlagsService,
                              ScmCredentialService scmCredentialService) {
        this.adapters = adapters;
        this.featureFlagsService = featureFlagsService;
        this.scmCredentialService = scmCredentialService;
        for (ScmProviderAdapter adapter : adapters) {
            adapterByType.put(adapter.getProviderType(), adapter);
            log.info("Registered SCM Provider Adapter: {} [{}]", adapter.getProviderType(), adapter.getClass().getSimpleName());
        }
    }

    /**
     * Resolves the matching SCM adapter for a given repository URL.
     * Prioritizes specific provider implementations (GitHub, Bitbucket, GitLab, GHES, Origin)
     * before falling back to the GENERIC Git provider.
     */
    public ScmProviderAdapter getAdapterForUrl(String repoUrl) {
        if (repoUrl != null && !repoUrl.isBlank()) {
            // 1. Strict match on specific provider adapters first
            for (ScmProviderAdapter adapter : adapters) {
                if (adapter.getProviderType() != ScmProviderType.GENERIC && adapter.supportsUrl(repoUrl)) {
                    return adapter;
                }
            }
            // 2. Explicit fallback to Generic adapter if available and supports URL
            ScmProviderAdapter genericAdapter = adapterByType.get(ScmProviderType.GENERIC);
            if (genericAdapter != null && genericAdapter.supportsUrl(repoUrl)) {
                return genericAdapter;
            }
        }
        // 3. Absolute fallback
        return adapterByType.getOrDefault(ScmProviderType.GENERIC,
                adapterByType.getOrDefault(ScmProviderType.GITHUB, adapters.get(0)));
    }

    /**
     * Retrieves an adapter by explicit provider type.
     */
    public ScmProviderAdapter getAdapter(ScmProviderType type) {
        return adapterByType.get(type);
    }

    /**
     * Returns all registered provider adapters.
     */
    public List<ScmProviderAdapter> getAllAdapters() {
        return Collections.unmodifiableList(adapters);
    }

    /**
     * Helper to get Git credentials for any URL.
     */
    public CredentialsProvider getGitCredentials(String repoUrl, String explicitToken) {
        ScmProviderAdapter adapter = getAdapterForUrl(repoUrl);
        return adapter != null ? adapter.getGitCredentials(repoUrl, explicitToken) : null;
    }

    /**
     * Helper to extract normalized full repository name (e.g. "owner/repo" or "workspace/repo_slug").
     */
    public String parseRepoFullName(String repoUrl) {
        ScmProviderAdapter adapter = getAdapterForUrl(repoUrl);
        return adapter != null ? adapter.parseRepoFullName(repoUrl) : null;
    }

    /**
     * Tests connectivity and credentials for a given provider type or repository request.
     */
    public PermissionCheckReport testConnection(String provider, TestConnectionRequest req) {
        TestConnectionRequest safeReq = req != null ? req : TestConnectionRequest.builder().build();
        if (safeReq.getCredentialId() == null || safeReq.getCredentialId().isBlank()) {
            return testConnectionUnlocked(provider, safeReq);
        }
        String installationId;
        try {
            installationId = installationForCheck(safeReq);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return installationFailure(provider, safeReq, e.getMessage());
        }
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(safeReq.getCredentialId(), installationId)) {
            PermissionCheckReport report = testConnectionUnlocked(provider, safeReq);
            stampBinding(report, safeReq.getCredentialId(), installationId);
            return report;
        }
    }

    /**
     * Installation token for this check. A caller-supplied id is used as-is when it belongs to the
     * credential. Otherwise a GitHub App with a repo URL is resolved to the installation that owns
     * that repo. The first selected install is not used once a repository is in play.
     */
    private String installationForCheck(TestConnectionRequest req) {
        ScmCredential cred = scmCredentialService.requireEnabled(req.getCredentialId());
        if (!cred.isGitHubApp()) {
            return null;
        }
        return scmCredentialService.resolveInstallationIdForRepo(cred, req.getRepoUrl(), req.getInstallationId());
    }

    private PermissionCheckReport installationFailure(String provider, TestConnectionRequest req, String message) {
        if (!PublicReadProbe.writeRequired(req) && !Boolean.TRUE.equals(req.getKnownPrivate())) {
            TestConnectionRequest anon = TestConnectionRequest.builder()
                    .repoUrl(req.getRepoUrl())
                    .requiredAccess(req.getRequiredAccess())
                    .knownPrivate(false)
                    .build();
            PermissionCheckReport pub = testConnectionUnlocked(provider, anon);
            if (pub != null && pub.isValid() && "PUBLIC".equals(pub.getAccessMode())) {
                return pub;
            }
        }
        String text = message != null ? message : "Could not resolve the GitHub App installation for this repository.";
        return PermissionCheckReport.builder()
                .valid(false)
                .repoFullName(req.getRepoUrl())
                .credentialId(req.getCredentialId())
                .httpStatusCode(404)
                .message(text)
                .errors(List.of(text))
                .passedChecks(List.of())
                .warnings(List.of())
                .build();
    }

    /** Record the credential and installation the check actually used. Public read stays unbound. */
    private void stampBinding(PermissionCheckReport report, String credentialId, String installationId) {
        if (report == null || "PUBLIC".equals(report.getAccessMode())) {
            return;
        }
        report.setCredentialId(credentialId);
        if (installationId == null || installationId.isBlank()) {
            return;
        }
        report.setInstallationId(installationId);
        report.setInstallationLogin(scmCredentialService.accountLoginForInstallation(credentialId, installationId));
    }

    private PermissionCheckReport testConnectionUnlocked(String provider, TestConnectionRequest safeReq) {
        if (provider != null && !provider.isBlank()) {
            featureFlagsService.requireProviderEnabled(provider);
        }
        if (safeReq.getRepoUrl() != null && !safeReq.getRepoUrl().isBlank()) {
            String detected = FeatureFlagsService.detectProviderFromUrl(safeReq.getRepoUrl());
            if (detected != null) {
                featureFlagsService.requireProviderEnabled(detected);
            }
        }
        boolean anonymousProbe = safeReq.getCredentialId() == null
                && (safeReq.getToken() == null || safeReq.getToken().isBlank())
                && !Boolean.TRUE.equals(safeReq.getKnownPrivate());
        if (anonymousProbe && !featureFlagsService.isPublicReposEnabled()) {
            return PermissionCheckReport.builder()
                    .valid(false)
                    .repoFullName(safeReq.getRepoUrl())
                    .httpStatusCode(403)
                    .message("Public repository access is disabled in Feature toggles.")
                    .errors(List.of("Enable \"Public repositories\" under Settings → Feature toggles, or use a credential."))
                    .passedChecks(List.of())
                    .warnings(List.of())
                    .build();
        }
        if (provider != null && !provider.isBlank()) {
            try {
                String norm = provider.trim().toUpperCase();
                if ("GHES".equalsIgnoreCase(norm)) norm = "GITHUB_ENTERPRISE";
                ScmProviderType type = ScmProviderType.valueOf(norm);
                ScmProviderAdapter adapter = getAdapter(type);
                if (adapter != null) {
                    return adapter.testConnection(safeReq);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception ignored) {
            }
        }
        if (safeReq.getRepoUrl() != null && !safeReq.getRepoUrl().isBlank()) {
            ScmProviderAdapter adapter = getAdapterForUrl(safeReq.getRepoUrl());
            if (adapter != null) {
                return adapter.testConnection(safeReq);
            }
        }
        ScmProviderAdapter defaultAdapter = getAdapter(ScmProviderType.GITHUB);
        return defaultAdapter != null
                ? defaultAdapter.testConnection(safeReq)
                : PermissionCheckReport.builder().valid(false).message("No SCM provider adapter found").build();
    }

    /**
     * Invalidate cached tokens across all registered adapters.
     */
    public void invalidateAllCaches() {
        for (ScmProviderAdapter adapter : adapters) {
            try {
                adapter.invalidateTokenCache();
            } catch (Exception e) {
                log.warn("Error invalidating cache for adapter {}: {}", adapter.getProviderType(), e.getMessage());
            }
        }
    }

    /**
     * Searches repositories across one or all SCM providers.
     */
    public RepoSearchResult searchRepositories(String query, String provider, int page, int limit) {
        String safeQuery = query != null ? query.trim() : "";
        String safeProvider = (provider != null && !provider.isBlank()) ? provider.trim().toUpperCase() : "";
        int safePage = Math.max(1, page);
        int safeLimit = Math.min(50, Math.max(5, limit));

        List<GitHubRepoOption> aggregated = new ArrayList<>();
        int total = 0;

        if ("ALL".equals(safeProvider) || safeProvider.isBlank()) {
            throw new IllegalArgumentException("provider is required; searching all providers is not supported.");
        }
        featureFlagsService.requireProviderEnabled(safeProvider);
        try {
            String norm = safeProvider;
            if ("GHES".equalsIgnoreCase(norm)) norm = "GITHUB_ENTERPRISE";
            ScmProviderType type = ScmProviderType.valueOf(norm);
            ScmProviderAdapter adapter = getAdapter(type);
            if (adapter != null) {
                RepoSearchResult res = adapter.searchRepositories(safeQuery, safePage, safeLimit);
                if (res != null && res.getItems() != null) {
                    aggregated.addAll(res.getItems());
                    total = res.getTotalCount();
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Provider search error for {}: {}", safeProvider, e.getMessage());
        }

        return RepoSearchResult.builder()
                .items(aggregated)
                .totalCount(total > 0 ? total : aggregated.size())
                .page(safePage)
                .perPage(safeLimit)
                .limit(safeLimit)
                .hasMore(aggregated.size() >= safeLimit)
                .provider(safeProvider)
                .query(safeQuery)
                .build();
    }

    /**
     * Lists accessible repositories for a provider or all providers.
     */
    public List<GitHubRepoOption> listAccessibleRepositories(String token, String provider) {
        String safeProvider = (provider != null && !provider.isBlank()) ? provider.trim().toUpperCase() : "ALL";
        List<GitHubRepoOption> list = new ArrayList<>();

        if ("ALL".equals(safeProvider) || safeProvider.isBlank()) {
            throw new IllegalArgumentException("provider is required; listing all providers is not supported.");
        }
        try {
            String norm = safeProvider;
            if ("GHES".equalsIgnoreCase(norm)) norm = "GITHUB_ENTERPRISE";
            ScmProviderType type = ScmProviderType.valueOf(norm);
            ScmProviderAdapter adapter = getAdapter(type);
            if (adapter != null) {
                List<GitHubRepoOption> repos = adapter.listAccessibleRepositories();
                if (repos != null) list.addAll(repos);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Provider list repos notice for {}: {}", safeProvider, e.getMessage());
        }
        return list;
    }

    /**
     * Creates a new remote repository on the appropriate SCM provider.
     */
    public GitHubRepoOption createRemoteRepository(CreateRepoRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("create repo request is required");
        }
        req.inferIdentityFromUrl();
        if (req.getCredentialId() != null) {
            String installationId = null;
            ScmCredential cred = scmCredentialService.requireEnabled(req.getCredentialId());
            if (cred.isGitHubApp() && req.getOwner() != null && !req.getOwner().isBlank()) {
                installationId = scmCredentialService.installationIdForAccount(cred, req.getOwner());
            }
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(req.getCredentialId(), installationId)) {
                GitHubRepoOption created = createRemoteRepositoryUnlocked(req);
                if (created != null) {
                    created.setCredentialId(req.getCredentialId());
                    if (installationId != null) {
                        created.setInstallationId(installationId);
                    }
                }
                return created;
            }
        }
        return createRemoteRepositoryUnlocked(req);
    }

    /** Cheap existence probe routed to the URL's adapter (bulk migration preflight + engine create stage). */
    public boolean repositoryExists(String repoUrl, String credentialId) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return false;
        }
        ScmProviderAdapter adapter = getAdapterForUrl(repoUrl);
        if (adapter == null) {
            return false;
        }
        if (credentialId != null) {
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credentialId)) {
                return adapter.repositoryExists(repoUrl);
            }
        }
        return adapter.repositoryExists(repoUrl);
    }

    /** Lightweight "has commits" probe routed to the URL's adapter (bulk migration Option 2). */
    public boolean hasCommits(String repoUrl, String credentialId) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return false;
        }
        ScmProviderAdapter adapter = getAdapterForUrl(repoUrl);
        if (adapter == null) {
            return false;
        }
        if (credentialId != null) {
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credentialId)) {
                return adapter.hasCommits(repoUrl);
            }
        }
        return adapter.hasCommits(repoUrl);
    }

    private GitHubRepoOption createRemoteRepositoryUnlocked(CreateRepoRequest req) {
        String targetUrl = req.getRepoUrl();
        ScmProviderAdapter adapter = getAdapterForUrl(targetUrl);
        if (adapter == null) {
            adapter = getAdapter(ScmProviderType.GITHUB);
        }

        if (adapter != null) {
            boolean created = adapter.createRemoteRepository(req);
            if (created) {
                String fullName = adapter.parseRepoFullName(targetUrl != null ? targetUrl : req.getName());
                return GitHubRepoOption.builder()
                        .name(req.getName() != null ? req.getName() : fullName)
                        .fullName(fullName != null ? fullName : req.getName())
                        .cloneUrl(targetUrl != null ? targetUrl : "")
                        .isPrivate(!"public".equals(req.resolvedVisibility()))
                        .visibility(req.resolvedVisibility())
                        .provider(adapter.getProviderType().name())
                        .hasWriteAccess(true)
                        .hasAdminAccess(true)
                        .build();
            }
        }
        throw new IllegalStateException("Failed to create remote repository on target SCM provider.");
    }
}
