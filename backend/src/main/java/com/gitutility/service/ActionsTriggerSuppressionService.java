package com.gitutility.service;

import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.provider.ghes.GitHubEnterpriseProviderService;
import com.gitutility.provider.github.GitHubProviderService;
import com.gitutility.repository.GitHubAppConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses GitHub/GHES Actions runs caused by GitMirror Hub writes on active remotes.
 * Requires App installation identity (not PAT) so runs can be attributed to a stable bot actor.
 */
@Service
@Slf4j
public class ActionsTriggerSuppressionService {

    private static final Set<ScmProviderType> SUPPORTED = Set.of(
            ScmProviderType.GITHUB,
            ScmProviderType.GITHUB_ENTERPRISE
    );

    private final SystemEngineConfigService systemEngineConfigService;
    private final GitHubAppConfigRepository configRepository;
    private final ScmProviderFacade scmProviderFacade;
    private final HubMetrics hubMetrics;
    private final GitHubProviderService gitHubProviderService;
    private final GitHubEnterpriseProviderService gitHubEnterpriseProviderService;
    private final ScmCredentialService scmCredentialService;


    private final Map<Long, Instant> jobStartedAt = new ConcurrentHashMap<>();

    public ActionsTriggerSuppressionService(
            @Lazy SystemEngineConfigService systemEngineConfigService,
            GitHubAppConfigRepository configRepository,
            ScmProviderFacade scmProviderFacade,
            HubMetrics hubMetrics,
            @Lazy GitHubProviderService gitHubProviderService,
            @Lazy GitHubEnterpriseProviderService gitHubEnterpriseProviderService,
            @Lazy ScmCredentialService scmCredentialService) {

        this.systemEngineConfigService = systemEngineConfigService;
        this.configRepository = configRepository;
        this.scmProviderFacade = scmProviderFacade;
        this.hubMetrics = hubMetrics;
        this.gitHubProviderService = gitHubProviderService;
        this.gitHubEnterpriseProviderService = gitHubEnterpriseProviderService;
        this.scmCredentialService = scmCredentialService;

    }
    public boolean isEnabled() {
        return systemEngineConfigService.getOrCreateConfig().isSuppressMirrorActionsTriggers();
    }

    public void beginJob(Long jobId) {
        if (jobId == null || !isEnabled()) {
            return;
        }
        // Small skew so runs created in the same second as the first push are included.
        jobStartedAt.put(jobId, Instant.now().minusSeconds(5));
    }

    public void endJob(Long jobId) {
        if (jobId != null) {
            jobStartedAt.remove(jobId);
        }
    }

    public boolean supportsRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank() || scmProviderFacade == null) {
            return false;
        }
        ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(repoUrl);
        return adapter != null && SUPPORTED.contains(adapter.getProviderType());
    }

    /**
     * Fail sync writes when suppression is on for GitHub/GHES but write auth is not a resolvable App bot.
     */
    public void validateWriteAuthOrThrow(String targetRepoUrl, String explicitWriteToken) {
        if (!isEnabled() || !supportsRepo(targetRepoUrl)) {
            return;
        }
        if (looksLikePat(explicitWriteToken)) {
            throw new IllegalStateException(
                    "Mirror Actions suppression is enabled, but the destination write token is a PAT. "
                            + "Configure GitHub App authentication (installation token) for the write side, "
                            + "or disable suppressMirrorActionsTriggers in System Engine settings.");
        }
        String botLogin = resolveBotLogin(targetRepoUrl);
        if (botLogin == null || botLogin.isBlank()) {
            GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
            boolean hasAppCreds = config != null
                    && config.getAppId() != null && !config.getAppId().isBlank()
                    && config.getPrivateKeyPem() != null && !config.getPrivateKeyPem().isBlank();
            throw new IllegalStateException(hasAppCreds
                    ? "Mirror Actions suppression is enabled, and GitHub App credentials are stored, but "
                            + "GET /app did not return an App slug (bot actor). Check App ID + private key, then retry."
                    : "Mirror Actions suppression is enabled, but the mirror App bot login is unknown. "
                            + "Save GitHub/GHES App credentials (App ID + private key) so the Hub can resolve "
                            + "the App slug/bot actor, or disable suppressMirrorActionsTriggers.");
        }
        if (explicitWriteToken != null && !explicitWriteToken.isBlank() && !looksLikeInstallationToken(explicitWriteToken)) {
            throw new IllegalStateException(
                    "Mirror Actions suppression is enabled, but the pair write token is not a GitHub App "
                            + "installation token (ghs_). Clear the pair token override to use the configured App, "
                            + "or disable suppressMirrorActionsTriggers.");
        }
        ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(targetRepoUrl);
        if (adapter != null && adapter.getProviderType() == ScmProviderType.GITHUB) {
            GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
            if (config != null && "PERSONAL_ACCESS_TOKEN".equalsIgnoreCase(config.getAuthType())
                    && (explicitWriteToken == null || explicitWriteToken.isBlank())) {
                throw new IllegalStateException(
                        "Mirror Actions suppression is enabled, but GitHub auth type is PERSONAL_ACCESS_TOKEN. "
                                + "Switch provider auth to GITHUB_APP or disable suppressMirrorActionsTriggers.");
            }
        }
        if (adapter != null && adapter.getProviderType() == ScmProviderType.GITHUB_ENTERPRISE) {
            GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
            if (config != null && "PERSONAL_ACCESS_TOKEN".equalsIgnoreCase(config.getGhesAuthType())
                    && (explicitWriteToken == null || explicitWriteToken.isBlank())) {
                throw new IllegalStateException(
                        "Mirror Actions suppression is enabled, but GHES auth type is PERSONAL_ACCESS_TOKEN. "
                                + "Switch GHES auth to GITHUB_APP or disable suppressMirrorActionsTriggers.");
            }
        }
    }

    /**
     * Cancels Actions runs on {@code targetRepoUrl} attributed to the mirror App since this job began.
     */
    public int suppressAfterWrite(String targetRepoUrl, Long jobId) {
        if (!isEnabled() || !supportsRepo(targetRepoUrl)) {
            return 0;
        }
        String botLogin = resolveBotLogin(targetRepoUrl);
        if (botLogin == null || botLogin.isBlank()) {
            log.warn("Actions suppression skipped for {}: bot login not resolved", targetRepoUrl);
            return 0;
        }
        Instant since = jobId != null
                ? jobStartedAt.getOrDefault(jobId, Instant.now().minusSeconds(600))
                : Instant.now().minusSeconds(600);
        ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(targetRepoUrl);
        String fullName = scmProviderFacade.parseRepoFullName(targetRepoUrl);
        if (adapter == null || fullName == null || fullName.isBlank()) {
            return 0;
        }
        try {
            int cancelled = adapter.cancelWorkflowRunsByActor(fullName, botLogin, since);
            if (cancelled > 0) {
                log.info("Cancelled {} Actions run(s) on {} for mirror actor {}", cancelled, fullName, botLogin);
                hubMetrics.recordActionsCancelled(cancelled);
            }
            return cancelled;
        } catch (Exception e) {
            log.warn("Failed to cancel Actions runs on {} for actor {}: {}", fullName, botLogin, e.getMessage());
            return 0;
        }
    }

    public String resolveBotLogin(String repoUrl) {
        if (!supportsRepo(repoUrl)) {
            return null;
        }
        Long credId = ScmCredentialContext.currentId();
        if (credId != null && scmCredentialService != null) {
            try {
                var cred = scmCredentialService.requireEnabled(credId);
                if (cred.getBotLogin() != null && !cred.getBotLogin().isBlank()) {
                    return cred.getBotLogin().trim();
                }
                if (cred.getAppSlug() != null && !cred.getAppSlug().isBlank()) {
                    return toBotLogin(cred.getAppSlug());
                }
            } catch (Exception e) {
                log.debug("Credential bot login resolve failed: {}", e.getMessage());
            }
        }
        GitHubAppConfig config = configRepository.findFirstByOrderByIdAsc().orElse(null);
        if (config == null) {
            return null;
        }
        ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(repoUrl);
        if (adapter != null && adapter.getProviderType() == ScmProviderType.GITHUB_ENTERPRISE) {
            if (config.getGhesBotLogin() != null && !config.getGhesBotLogin().isBlank()) {
                return config.getGhesBotLogin().trim();
            }
            if (config.getGhesAppSlug() != null && !config.getGhesAppSlug().isBlank()) {
                return toBotLogin(config.getGhesAppSlug());
            }
            if (gitHubEnterpriseProviderService != null) {
                return gitHubEnterpriseProviderService.resolveAndPersistBotLogin(config);
            }
            return null;
        }
        if (config.getBotLogin() != null && !config.getBotLogin().isBlank()) {
            return config.getBotLogin().trim();
        }
        if (config.getAppSlug() != null && !config.getAppSlug().isBlank()) {
            return toBotLogin(config.getAppSlug());
        }
        if (gitHubProviderService != null) {
            return gitHubProviderService.resolveAndPersistBotLogin(config);
        }
        return null;
    }

    public static String toBotLogin(String appSlug) {
        if (appSlug == null || appSlug.isBlank()) {
            return null;
        }
        String slug = appSlug.trim();
        if (slug.endsWith("[bot]")) {
            return slug;
        }
        return slug + "[bot]";
    }

    public static boolean looksLikeInstallationToken(String token) {
        return token != null && token.trim().startsWith("ghs_");
    }

    public static boolean looksLikePat(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim();
        return t.startsWith("ghp_")
                || t.startsWith("github_pat_")
                || t.startsWith("gho_")
                || t.startsWith("ghu_");
    }
}
