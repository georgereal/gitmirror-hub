package com.gitutility.provider;

import com.gitutility.model.dto.*;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.service.ScmCredentialContext;
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

    public ScmProviderFacade(List<ScmProviderAdapter> adapters) {
        this.adapters = adapters;
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
        if (safeReq.getCredentialId() != null) {
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(safeReq.getCredentialId())) {
                return testConnectionUnlocked(provider, safeReq);
            }
        }
        return testConnectionUnlocked(provider, safeReq);
    }

    private PermissionCheckReport testConnectionUnlocked(String provider, TestConnectionRequest safeReq) {
        if (provider != null && !provider.isBlank()) {
            try {
                String norm = provider.trim().toUpperCase();
                if ("GHES".equalsIgnoreCase(norm)) norm = "GITHUB_ENTERPRISE";
                ScmProviderType type = ScmProviderType.valueOf(norm);
                ScmProviderAdapter adapter = getAdapter(type);
                if (adapter != null) {
                    return adapter.testConnection(safeReq);
                }
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
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(req.getCredentialId())) {
                return createRemoteRepositoryUnlocked(req);
            }
        }
        return createRemoteRepositoryUnlocked(req);
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
                        .isPrivate(req.getIsPrivate() == null || req.getIsPrivate())
                        .provider(adapter.getProviderType().name())
                        .hasWriteAccess(true)
                        .hasAdminAccess(true)
                        .build();
            }
        }
        throw new IllegalStateException("Failed to create remote repository on target SCM provider.");
    }
}
