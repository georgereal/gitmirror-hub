package com.gitutility.service;

import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.repository.GitHubAppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a stable installation key for GitHub / GHES App (or PAT) quota buckets.
 */
@Component
@RequiredArgsConstructor
public class ScmInstallationKeyResolver {

    private final GitHubAppConfigRepository configRepository;
    private final com.gitutility.repository.ScmCredentialRepository credentialRepository;

    public String resolve(String provider) {
        Long credId = ScmCredentialContext.currentId();
        if (credId != null && credentialRepository != null) {
            return credentialRepository.findById(credId)
                    .map(c -> {
                        if (c.getInstallationId() != null && !c.getInstallationId().isBlank()) {
                            return "install:" + c.getInstallationId().trim();
                        }
                        if (c.isGitHubApp()) {
                            return "app:" + (c.getAppId() != null ? c.getAppId() : "");
                        }
                        return c.getProvider() + "-pat:" + c.getId();
                    })
                    .orElse("default");
        }
        GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
        if (config == null) {
            return "default";
        }
        String p = provider == null ? "" : provider.toLowerCase();
        if (p.contains("enterprise") || p.equals("ghes")) {
            if (config.getGhesInstallationId() != null && !config.getGhesInstallationId().isBlank()) {
                return "install:" + config.getGhesInstallationId().trim();
            }
            if ("GITHUB_APP".equalsIgnoreCase(config.getGhesAuthType())) {
                return "ghes-app:" + nullToEmpty(config.getGhesAppId());
            }
            return "ghes-pat";
        }
        if (p.contains("github") || p.isBlank()) {
            if (config.getInstallationId() != null && !config.getInstallationId().isBlank()) {
                return "install:" + config.getInstallationId().trim();
            }
            if ("GITHUB_APP".equalsIgnoreCase(config.getAuthType())) {
                return "app:" + nullToEmpty(config.getAppId());
            }
            return "github-pat";
        }
        return p;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
