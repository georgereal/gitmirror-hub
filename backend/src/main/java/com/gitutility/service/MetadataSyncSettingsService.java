package com.gitutility.service;

import com.gitutility.model.dto.MetadataSyncSettingsRequest;
import com.gitutility.model.dto.MetadataSyncSettingsResponse;
import com.gitutility.model.entity.MetadataSyncSettings;
import com.gitutility.repository.MetadataSyncSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global on/off switches for pull requests, releases, CI checks, and LFS.
 * A missing row is treated as all enabled.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataSyncSettingsService {

    static final String DISABLED = "Disabled in metadata settings";

    private final MetadataSyncSettingsRepository repository;

    @PostConstruct
    public void init() {
        getOrCreate();
    }

    @Transactional
    public MetadataSyncSettings getOrCreate() {
        return repository.findTopByOrderByIdAsc().orElseGet(() -> {
            log.info("Seeding metadata sync settings with every switch on");
            return repository.save(MetadataSyncSettings.builder().build());
        });
    }

    public MetadataSyncSettingsResponse getResponse() {
        return MetadataSyncSettingsResponse.fromEntity(getOrCreate());
    }

    @Transactional
    public MetadataSyncSettingsResponse update(MetadataSyncSettingsRequest req) {
        MetadataSyncSettings config = getOrCreate();
        if (req == null) {
            return MetadataSyncSettingsResponse.fromEntity(config);
        }
        if (req.getPullRequestsEnabled() != null) {
            config.setPullRequestsEnabled(req.getPullRequestsEnabled());
        }
        if (req.getReleasesEnabled() != null) {
            config.setReleasesEnabled(req.getReleasesEnabled());
        }
        if (req.getCiChecksEnabled() != null) {
            config.setCiChecksEnabled(req.getCiChecksEnabled());
        }
        if (req.getLfsEnabled() != null) {
            config.setLfsEnabled(req.getLfsEnabled());
        }
        return MetadataSyncSettingsResponse.fromEntity(repository.save(config));
    }

    public boolean isPullRequestsEnabled() {
        return getOrCreate().isPullRequestsEnabled();
    }

    public boolean isReleasesEnabled() {
        return getOrCreate().isReleasesEnabled();
    }

    public boolean isCiChecksEnabled() {
        return getOrCreate().isCiChecksEnabled();
    }

    public boolean isLfsEnabled() {
        return getOrCreate().isLfsEnabled();
    }

    public void requirePullRequestsEnabled() {
        if (!isPullRequestsEnabled()) {
            throw new IllegalStateException(
                    "Pull request sync is turned off in Settings → Metadata sync.");
        }
    }

    public void requireReleasesEnabled() {
        if (!isReleasesEnabled()) {
            throw new IllegalStateException(
                    "Release sync is turned off in Settings → Metadata sync.");
        }
    }

    public void requireCiChecksEnabled() {
        if (!isCiChecksEnabled()) {
            throw new IllegalStateException(
                    "CI check sync is turned off in Settings → Metadata sync.");
        }
    }

    public void requireLfsEnabled() {
        if (!isLfsEnabled()) {
            throw new IllegalStateException(
                    "Git LFS sync is turned off in Settings → Metadata sync.");
        }
    }
}
