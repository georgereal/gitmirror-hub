package com.gitutility.service;

import com.gitutility.model.dto.FeatureFlagsRequest;
import com.gitutility.model.dto.FeatureFlagsResponse;
import com.gitutility.model.entity.FeatureFlagsConfig;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.repository.FeatureFlagsConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagsService {

    private final FeatureFlagsConfigRepository repository;

    @Value("${git-utility.features.public-repos-enabled:true}")
    private boolean defaultPublicReposEnabled;

    @Value("${git-utility.features.provider-gitlab-enabled:false}")
    private boolean defaultProviderGitlabEnabled;

    @Value("${git-utility.features.provider-bitbucket-enabled:false}")
    private boolean defaultProviderBitbucketEnabled;

    @Value("${git-utility.features.provider-origin-enabled:false}")
    private boolean defaultProviderOriginEnabled;

    @Value("${git-utility.features.provider-generic-enabled:false}")
    private boolean defaultProviderGenericEnabled;

    @PostConstruct
    public void init() {
        getOrCreate();
    }

    @Transactional
    public FeatureFlagsConfig getOrCreate() {
        return repository.findTopByOrderByIdAsc().orElseGet(() -> {
            log.info("Seeding FeatureFlagsConfig from env defaults (publicRepos={}, gitlab={}, bitbucket={}, origin={}, generic={})",
                    defaultPublicReposEnabled, defaultProviderGitlabEnabled, defaultProviderBitbucketEnabled,
                    defaultProviderOriginEnabled, defaultProviderGenericEnabled);
            FeatureFlagsConfig config = FeatureFlagsConfig.builder()
                    .publicReposEnabled(defaultPublicReposEnabled)
                    .providerGitlabEnabled(defaultProviderGitlabEnabled)
                    .providerBitbucketEnabled(defaultProviderBitbucketEnabled)
                    .providerOriginEnabled(defaultProviderOriginEnabled)
                    .providerGenericEnabled(defaultProviderGenericEnabled)
                    .build();
            return repository.save(config);
        });
    }

    public FeatureFlagsResponse getResponse() {
        return FeatureFlagsResponse.fromEntity(getOrCreate());
    }

    @Transactional
    public FeatureFlagsResponse update(FeatureFlagsRequest req) {
        FeatureFlagsConfig config = getOrCreate();
        if (req == null) {
            return FeatureFlagsResponse.fromEntity(config);
        }
        if (req.getPublicReposEnabled() != null) {
            config.setPublicReposEnabled(req.getPublicReposEnabled());
        }
        if (req.getProviderGitlabEnabled() != null) {
            config.setProviderGitlabEnabled(req.getProviderGitlabEnabled());
        }
        if (req.getProviderBitbucketEnabled() != null) {
            config.setProviderBitbucketEnabled(req.getProviderBitbucketEnabled());
        }
        if (req.getProviderOriginEnabled() != null) {
            config.setProviderOriginEnabled(req.getProviderOriginEnabled());
        }
        if (req.getProviderGenericEnabled() != null) {
            config.setProviderGenericEnabled(req.getProviderGenericEnabled());
        }
        return FeatureFlagsResponse.fromEntity(repository.save(config));
    }

    public boolean isPublicReposEnabled() {
        return getOrCreate().isPublicReposEnabled();
    }

    public boolean isProviderEnabled(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return true;
        }
        String key = providerKey.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        FeatureFlagsConfig f = getOrCreate();
        return switch (key) {
            case "GITHUB", "GHES", "GITHUB_ENTERPRISE", "GITHUB_CLOUD" -> true;
            case "GITLAB" -> f.isProviderGitlabEnabled();
            case "BITBUCKET" -> f.isProviderBitbucketEnabled();
            case "ORIGIN", "CURSOR_ORIGIN" -> f.isProviderOriginEnabled();
            case "GENERIC", "AZURE", "AZURE_DEVOPS" -> f.isProviderGenericEnabled();
            default -> true;
        };
    }

    public void requireProviderEnabled(String providerKey) {
        if (!isProviderEnabled(providerKey)) {
            throw new IllegalArgumentException(
                    "Provider '" + providerKey + "' is disabled in Feature toggles. Enable it under Settings → Feature toggles.");
        }
    }

    public void requirePublicReposAllowed() {
        if (!isPublicReposEnabled()) {
            throw new IllegalArgumentException(
                    "Public repositories are disabled in Feature toggles. Enable \"Public repositories\" under Settings → Feature toggles, or use a private/credentialed source.");
        }
    }

    /**
     * Block disabled product paths on pair create/update.
     */
    public void assertMappingAllowed(RepoMapping mapping) {
        if (mapping == null) {
            return;
        }
        if (!isPublicReposEnabled()) {
            if (mapping.getSourceVisibility() == RepoVisibility.PUBLIC) {
                requirePublicReposAllowed();
            }
            if (mapping.getTargetVisibility() == RepoVisibility.PUBLIC) {
                requirePublicReposAllowed();
            }
            if (Boolean.TRUE.equals(mapping.getSourcePublicRead())) {
                requirePublicReposAllowed();
            }
        }
        assertUrlProviderAllowed(mapping.getRepoAUrl(), "Source");
        assertUrlProviderAllowed(mapping.getRepoBUrl(), "Destination");
        assertNamedProviderAllowed(mapping.getSourceProvider(), "Source");
        assertNamedProviderAllowed(mapping.getTargetProvider(), "Destination");
    }

    private void assertNamedProviderAllowed(String provider, String side) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        if (!isProviderEnabled(provider)) {
            throw new IllegalArgumentException(
                    side + " provider '" + provider + "' is disabled in Feature toggles.");
        }
    }

    private void assertUrlProviderAllowed(String repoUrl, String side) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return;
        }
        String detected = detectProviderFromUrl(repoUrl);
        if (detected != null && !isProviderEnabled(detected)) {
            throw new IllegalArgumentException(
                    side + " URL uses provider '" + detected + "', which is disabled in Feature toggles.");
        }
    }

    public static String detectProviderFromUrl(String repoUrl) {
        if (repoUrl == null) {
            return null;
        }
        String lower = repoUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("gitlab.com") || lower.contains("gitlab.")) {
            return "GITLAB";
        }
        if (lower.contains("bitbucket.org") || lower.contains("bitbucket.")) {
            return "BITBUCKET";
        }
        if (lower.contains("origin.cursor.com")) {
            return "ORIGIN";
        }
        if (lower.contains("dev.azure.com") || lower.contains("visualstudio.com")) {
            return "GENERIC";
        }
        if (lower.contains("github.com")) {
            return "GITHUB";
        }
        // GHES / unknown github appliance host — still GitHub family
        if (lower.contains("github.") && !lower.contains("gitlab")) {
            return "GHES";
        }
        return null;
    }
}
