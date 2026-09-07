package com.gitutility.service;

import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReleaseAndStatusSyncService {

    private final ScmProviderFacade scmProviderFacade;

    /**
     * Replicates external CI/CD commit statuses across mirrored repositories for a given commit SHA.
     * Delegates to the target provider adapter (GitHub Cloud, GHES, Bitbucket, GitLab).
     */
    public boolean replicateCommitStatus(RepoMapping mapping, String sha, String state, String targetUrl, String description, String context) {
        if (mapping == null || sha == null || sha.isBlank()) return false;

        String targetFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoBUrl());
        if (targetFullName == null) return false;

        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(mapping.getRepoBUrl());
        return targetAdapter.replicateCommitStatus(targetFullName, sha, state, targetUrl, description, context);
    }

    /**
     * Synchronizes Releases and attached binary release assets across repositories.
     */
    public int syncReleases(Long mappingId, String sourceRepoUrl, String targetRepoUrl) {
        return syncReleases(mappingId, sourceRepoUrl, targetRepoUrl, null);
    }

    public int syncReleases(Long mappingId, String sourceRepoUrl, String targetRepoUrl, Consumer<String> progress) {
        String sourceFullName = scmProviderFacade.parseRepoFullName(sourceRepoUrl);
        String targetFullName = scmProviderFacade.parseRepoFullName(targetRepoUrl);

        if (sourceFullName == null || targetFullName == null) return 0;

        ScmProviderAdapter sourceAdapter = scmProviderFacade.getAdapterForUrl(sourceRepoUrl);

        try {
            if (progress != null) {
                progress.accept("Releases · Fetching release list from " + sourceFullName + "...");
            }
            List<SyncDiffReport.ReleaseDetail> sourceReleases = sourceAdapter.listReleases(sourceFullName);
            log.info("Discovered {} release(s) on source {} ({})", sourceReleases.size(), sourceFullName, sourceAdapter.getProviderType());
            if (progress != null) {
                progress.accept("Releases · Found " + sourceReleases.size() + " release(s) on " + sourceFullName);
            }
            return sourceReleases.size();
        } catch (Exception e) {
            log.warn("Releases synchronization notice: {}", e.getMessage());
            return 0;
        }
    }
}
