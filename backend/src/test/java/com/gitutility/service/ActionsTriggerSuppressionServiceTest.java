package com.gitutility.service;

import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.entity.SystemEngineConfig;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.provider.ghes.GitHubEnterpriseProviderService;
import com.gitutility.provider.github.GitHubProviderService;
import com.gitutility.repository.GitHubAppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActionsTriggerSuppressionServiceTest {

    @Mock
    private SystemEngineConfigService systemEngineConfigService;
    @Mock
    private GitHubAppConfigRepository configRepository;
    @Mock
    private ScmProviderFacade scmProviderFacade;
    @Mock
    private ScmProviderAdapter githubAdapter;
    @Mock
    private HubMetrics hubMetrics;
    @Mock
    private GitHubProviderService gitHubProviderService;
    @Mock
    private GitHubEnterpriseProviderService gitHubEnterpriseProviderService;

    private ActionsTriggerSuppressionService service;

    @BeforeEach
    void setUp() {
        service = new ActionsTriggerSuppressionService(
                systemEngineConfigService, configRepository, scmProviderFacade, hubMetrics,
                gitHubProviderService, gitHubEnterpriseProviderService, null);

    }

    @Test
    void toBotLoginAppendsBotSuffix() {
        assertEquals("gitmirror-hub[bot]", ActionsTriggerSuppressionService.toBotLogin("gitmirror-hub"));
        assertEquals("gitmirror-hub[bot]", ActionsTriggerSuppressionService.toBotLogin("gitmirror-hub[bot]"));
        assertNull(ActionsTriggerSuppressionService.toBotLogin(null));
    }

    @Test
    void looksLikePatAndInstallationToken() {
        assertTrue(ActionsTriggerSuppressionService.looksLikePat("ghp_abc"));
        assertTrue(ActionsTriggerSuppressionService.looksLikePat("github_pat_xyz"));
        assertFalse(ActionsTriggerSuppressionService.looksLikePat("ghs_abc"));
        assertTrue(ActionsTriggerSuppressionService.looksLikeInstallationToken("ghs_abc"));
        assertFalse(ActionsTriggerSuppressionService.looksLikeInstallationToken("ghp_abc"));
    }

    @Test
    void validateWriteAuthFailsForPatWhenEnabled() {
        when(systemEngineConfigService.getOrCreateConfig()).thenReturn(
                SystemEngineConfig.builder().suppressMirrorActionsTriggers(true).build());
        when(scmProviderFacade.getAdapterForUrl(anyString())).thenReturn(githubAdapter);
        when(githubAdapter.getProviderType()).thenReturn(ScmProviderType.GITHUB);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.validateWriteAuthOrThrow("https://github.com/acme/repo", "ghp_secret"));
        assertTrue(ex.getMessage().contains("PAT"));
    }

    @Test
    void validateWriteAuthPassesWhenDisabled() {
        when(systemEngineConfigService.getOrCreateConfig()).thenReturn(
                SystemEngineConfig.builder().suppressMirrorActionsTriggers(false).build());
        assertDoesNotThrow(() -> service.validateWriteAuthOrThrow(
                "https://github.com/acme/repo", "ghp_secret"));
    }

    @Test
    void validateWriteAuthResolvesBotFromStoredAppWhenSlugMissing() {
        when(systemEngineConfigService.getOrCreateConfig()).thenReturn(
                SystemEngineConfig.builder().suppressMirrorActionsTriggers(true).build());
        when(scmProviderFacade.getAdapterForUrl(anyString())).thenReturn(githubAdapter);
        when(githubAdapter.getProviderType()).thenReturn(ScmProviderType.GITHUB);
        GitHubAppConfig stored = GitHubAppConfig.builder()
                .authType("GITHUB_APP")
                .appId("4751561")
                .privateKeyPem("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----")
                .build();
        when(configRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(stored));
        when(gitHubProviderService.resolveAndPersistBotLogin(stored)).thenReturn("gitmirror-hub[bot]");

        assertDoesNotThrow(() -> service.validateWriteAuthOrThrow("https://github.com/acme/repo", null));
        verify(gitHubProviderService).resolveAndPersistBotLogin(stored);
    }

    @Test
    void suppressAfterWriteCancelsOnlyViaAdapterForMirrorBot() {
        when(systemEngineConfigService.getOrCreateConfig()).thenReturn(
                SystemEngineConfig.builder().suppressMirrorActionsTriggers(true).build());
        when(scmProviderFacade.getAdapterForUrl(anyString())).thenReturn(githubAdapter);
        when(githubAdapter.getProviderType()).thenReturn(ScmProviderType.GITHUB);
        when(scmProviderFacade.parseRepoFullName(anyString())).thenReturn("acme/repo");
        when(configRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(
                GitHubAppConfig.builder().botLogin("gitmirror-hub[bot]").build()));
        when(githubAdapter.cancelWorkflowRunsByActor(eq("acme/repo"), eq("gitmirror-hub[bot]"), any(Instant.class)))
                .thenReturn(2);

        service.beginJob(42L);
        int cancelled = service.suppressAfterWrite("https://github.com/acme/repo.git", 42L);
        assertEquals(2, cancelled);
        verify(githubAdapter).cancelWorkflowRunsByActor(eq("acme/repo"), eq("gitmirror-hub[bot]"), any(Instant.class));
        verify(githubAdapter, never()).cancelWorkflowRunsByActor(eq("acme/repo"), eq("someone-else"), any());
        service.endJob(42L);
    }

    @Test
    void suppressAfterWriteSkipsWhenBotLoginMissing() {
        when(systemEngineConfigService.getOrCreateConfig()).thenReturn(
                SystemEngineConfig.builder().suppressMirrorActionsTriggers(true).build());
        when(scmProviderFacade.getAdapterForUrl(anyString())).thenReturn(githubAdapter);
        when(githubAdapter.getProviderType()).thenReturn(ScmProviderType.GITHUB);
        when(configRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(
                GitHubAppConfig.builder().build()));

        assertEquals(0, service.suppressAfterWrite("https://github.com/acme/repo", 1L));
        verify(githubAdapter, never()).cancelWorkflowRunsByActor(any(), any(), any());
    }

    @Test
    void isCancellableWorkflowStatus() {
        assertTrue(com.gitutility.provider.github.GitHubProviderService.isCancellableWorkflowStatus("queued"));
        assertTrue(com.gitutility.provider.github.GitHubProviderService.isCancellableWorkflowStatus("in_progress"));
        assertFalse(com.gitutility.provider.github.GitHubProviderService.isCancellableWorkflowStatus("completed"));
    }
}
