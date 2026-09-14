package com.gitutility.service;

import com.gitutility.model.entity.ScmCredential;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.ScmCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScmCredentialServiceTest {

    @Mock
    private ScmCredentialRepository credentialRepository;
    @Mock
    private GitHubAppConfigRepository legacyConfigRepository;
    @Mock
    private RepoMappingRepository mappingRepository;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private FeatureFlagsService featureFlagsService;

    private ScmCredentialService service;

    @BeforeEach
    void setUp() {
        service = new ScmCredentialService(
                credentialRepository, legacyConfigRepository, mappingRepository, restTemplate, featureFlagsService);
    }

    @Test
    void patCredentialNeverMintsInstallToken() {
        ScmCredential cred = ScmCredential.builder()
                .id(1L)
                .label("tools-pat")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("PERSONAL_ACCESS_TOKEN")
                .patToken("ghp_only_this")
                .privateKeyPem("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----")
                .appId("999")
                .enabled(true)
                .build();

        assertEquals("ghp_only_this", service.resolveAccessToken(cred));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void appCredentialRequiresInstallationAndIgnoresPatField() {
        ScmCredential cred = ScmCredential.builder()
                .id(2L)
                .label("acme-app")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("GITHUB_APP")
                .appId("1")
                .privateKeyPem("not-a-real-pem")
                .patToken("ghp_MUST_NOT_BE_USED")
                .enabled(true)
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.resolveAccessToken(cred));
        assertTrue(ex.getMessage().contains("installation"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void searchKeepsReposWhenGithubReportsNoPullPermission() {
        ScmCredential cred = ScmCredential.builder()
                .id(3L)
                .label("tools-pat")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("PERSONAL_ACCESS_TOKEN")
                .patToken("ghp_x")
                .enabled(true)
                .build();
        when(credentialRepository.findById(3L)).thenReturn(Optional.of(cred));
        String body = """
                [{"id":1,"full_name":"acme/test-repo","clone_url":"https://github.com/acme/test-repo.git",
                "permissions":{"pull":false,"push":false}}]
                """;
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        var res = service.searchRepositories(3L, "", 1, 15, "PULL");
        assertEquals(1, res.getTotalCount());
        assertEquals("acme/test-repo", res.getItems().get(0).getFullName());
    }

    @Test
    void hmacSecretADoesNotAuthenticateSecretB() throws Exception {
        ScmCredential a = ScmCredential.builder()
                .id(3L)
                .label("a")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("PERSONAL_ACCESS_TOKEN")
                .webhookSecret("secret-a")
                .enabled(true)
                .build();
        String payload = "{\"zen\":\"ok\"}";
        String sigB = "sha256=" + hmacHex("secret-b", payload);
        assertFalse(service.hmacMatches(a, payload, sigB));
        String sigA = "sha256=" + hmacHex("secret-a", payload);
        assertTrue(service.hmacMatches(a, payload, sigA));
    }

    @Test
    void searchRejectsAllProvidersViaNormalize() {
        assertEquals("GITHUB", ScmCredentialService.normalizeProvider("github"));
        assertEquals("GITHUB_ENTERPRISE", ScmCredentialService.normalizeProvider("GHES"));
    }

    @Test
    void ensureBoundIfUniqueBindsWhenExactlyOneGithubCredential() {
        ScmCredential cred = ScmCredential.builder()
                .id(1L)
                .label("only")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("GITHUB_APP")
                .enabled(true)
                .build();
        var mapping = new com.gitutility.model.entity.RepoMapping();
        mapping.setName("legacy-pair");
        mapping.setRepoAUrl("https://github.com/acme/src.git");
        mapping.setRepoBUrl("https://github.com/acme/dst.git");
        when(credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc("GITHUB"))
                .thenReturn(List.of(cred));
        when(credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc("GITHUB_ENTERPRISE"))
                .thenReturn(List.of());
        when(mappingRepository.save(mapping)).thenReturn(mapping);

        assertTrue(service.ensureBoundIfUnique(mapping));
        assertEquals(1L, mapping.getSourceCredentialId());
        assertEquals(1L, mapping.getTargetCredentialId());
        verify(mappingRepository).save(mapping);
    }

    @Test
    void ensureBoundIfUniqueSkipsWhenMultipleGithubCredentials() {
        ScmCredential a = ScmCredential.builder().id(1L).provider("GITHUB").enabled(true).build();
        ScmCredential b = ScmCredential.builder().id(2L).provider("GITHUB").enabled(true).build();
        var mapping = new com.gitutility.model.entity.RepoMapping();
        mapping.setRepoAUrl("https://github.com/acme/src.git");
        mapping.setRepoBUrl("https://bitbucket.org/ws/dst.git");
        when(credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc("GITHUB"))
                .thenReturn(List.of(a, b));
        when(credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc("GITHUB_ENTERPRISE"))
                .thenReturn(List.of());

        assertFalse(service.ensureBoundIfUnique(mapping));
        assertNull(mapping.getSourceCredentialId());
        verify(mappingRepository, never()).save(any());
    }

    @Test
    void requireBoundIfGithubFailsClosedWithoutIds() {
        var mapping = new com.gitutility.model.entity.RepoMapping();
        mapping.setRepoAUrl("https://github.com/acme/src.git");
        mapping.setRepoBUrl("https://github.com/acme/dst.git");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.requireBoundIfGithub(mapping));
        assertTrue(ex.getMessage().toLowerCase().contains("source"));
    }

    @Test
    void requireBoundIfGithubAllowsUnboundPublicSource() {
        doNothing().when(featureFlagsService).requirePublicReposAllowed();
        var mapping = new com.gitutility.model.entity.RepoMapping();
        mapping.setRepoAUrl("https://github.com/microsoft/vscode.git");
        mapping.setRepoBUrl("https://github.com/acme/mirror-vscode.git");
        mapping.setSourceVisibility(com.gitutility.model.enums.RepoVisibility.PUBLIC);
        mapping.setTargetCredentialId(9L);
        assertDoesNotThrow(() -> service.requireBoundIfGithub(mapping));
    }

    @Test
    void requireBoundIfGithubBlocksPublicWhenFeatureDisabled() {
        doThrow(new IllegalArgumentException("Public repositories are disabled"))
                .when(featureFlagsService).requirePublicReposAllowed();
        var mapping = new com.gitutility.model.entity.RepoMapping();
        mapping.setRepoAUrl("https://github.com/microsoft/vscode.git");
        mapping.setRepoBUrl("https://github.com/acme/mirror-vscode.git");
        mapping.setSourceVisibility(com.gitutility.model.enums.RepoVisibility.PUBLIC);
        mapping.setTargetCredentialId(9L);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.requireBoundIfGithub(mapping));
        assertTrue(ex.getMessage().toLowerCase().contains("public"));
    }

    @Test
    void requireBoundIfGithubStillRequiresDestCredentialForWrite() {
        var mapping = new com.gitutility.model.entity.RepoMapping();
        mapping.setRepoAUrl("https://github.com/microsoft/vscode.git");
        mapping.setRepoBUrl("https://github.com/acme/mirror-vscode.git");
        mapping.setSourceVisibility(com.gitutility.model.enums.RepoVisibility.PUBLIC);
        mapping.setSyncDirection(com.gitutility.model.enums.SyncDirection.UNIDIRECTIONAL_A_TO_B);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.requireBoundIfGithub(mapping));
        assertTrue(ex.getMessage().toLowerCase().contains("destination"));
    }

    @Test
    void inaccessibleRepoThrowsInstallationMismatch() {
        ScmCredential cred = ScmCredential.builder()
                .label("old-org")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("PERSONAL_ACCESS_TOKEN")
                .patToken("ghp_x")
                .accountLogin("old-org")
                .enabled(true)
                .build();
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        AuthInstallationMismatchException mismatch = assertThrows(AuthInstallationMismatchException.class,
                () -> service.assertRepoAccessible(cred, "https://github.com/new-org/repo.git"));
        assertTrue(mismatch.getMessage().contains("cannot access") || mismatch.getMessage().contains(AuthInstallationMismatchException.CODE));
    }

    @Test
    void findByInstallationMatchesIdsInJsonList() {
        ScmCredential cred = ScmCredential.builder()
                .id(7L)
                .label("multi")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("GITHUB_APP")
                .appId("1")
                .installationId("111")
                .installationIdsJson("[\"111\",\"222\"]")
                .enabled(true)
                .build();
        when(credentialRepository.findByInstallationIdAndProvider("222", "GITHUB"))
                .thenReturn(Optional.empty());
        when(credentialRepository.findByProviderOrderByIdAsc("GITHUB")).thenReturn(List.of(cred));
        assertEquals(cred, service.findByInstallation("222", "GITHUB"));
    }

    @Test
    void installationPermissionsExposeCreateRepoGate() throws Exception {
        ScmCredential cred = ScmCredential.builder()
                .id(7L)
                .label("acme-app")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("GITHUB_APP")
                .appId("42")
                .privateKeyPem(rsaPem())
                .enabled(true)
                .build();
        when(credentialRepository.findById(7L)).thenReturn(Optional.of(cred));
        String body = """
                [
                  {"id":111,"account":{"login":"acme-org","type":"Organization"},"repository_selection":"selected",
                   "html_url":"https://github.com/organizations/acme-org/settings/installations/111",
                   "permissions":{"contents":"write","metadata":"read","administration":"write"}},
                  {"id":222,"account":{"login":"other-user","type":"User"},"repository_selection":"selected",
                   "permissions":{"contents":"write","metadata":"read","administration":"read"}},
                  {"id":333,"account":{"login":"legacy-user","type":"User"},"repository_selection":"all"},
                  {"id":444,"account":{"login":"noadmin-user","type":"User"},"repository_selection":"selected",
                   "permissions":{"contents":"read","metadata":"read"}}
                ]
                """;
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        var installs = service.listInstallations(7L);
        assertEquals(4, installs.size());
        assertEquals(Boolean.TRUE, installs.get(0).getCanCreateRepo());
        assertEquals(Boolean.FALSE, installs.get(1).getCanCreateRepo());
        assertNull(installs.get(2).getCanCreateRepo());
        assertEquals(Boolean.FALSE, installs.get(3).getCanCreateRepo());
        assertEquals("write", installs.get(0).getPermissions().get("administration"));

        // Preflight blocks only on positive knowledge that the installation lacks administration:write.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.assertCanCreateRepository(7L, "other-user"));
        assertTrue(ex.getMessage().contains("Administration"));

        // A permissions object without an administration key is also positive knowledge:
        // GitHub omits ungranted permissions, so this is exactly the fresh-App-without-Administration case.
        IllegalArgumentException noAdmin = assertThrows(IllegalArgumentException.class,
                () -> service.assertCanCreateRepository(7L, "noadmin-user"));
        assertTrue(noAdmin.getMessage().contains("Administration"));

        assertDoesNotThrow(() -> service.assertCanCreateRepository(7L, "acme-org"));
        assertDoesNotThrow(() -> service.assertCanCreateRepository(7L, "unknown-org"));
        // Legacy host without any permissions node stays unknown — GitHub remains the final arbiter.
        assertDoesNotThrow(() -> service.assertCanCreateRepository(7L, "legacy-user"));
    }

    @Test
    void createRepoPreflightNeverBlocksPatCredentials() {
        ScmCredential cred = ScmCredential.builder()
                .id(8L)
                .label("pat")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("PERSONAL_ACCESS_TOKEN")
                .patToken("ghp_x")
                .enabled(true)
                .build();
        when(credentialRepository.findById(8L)).thenReturn(Optional.of(cred));

        assertDoesNotThrow(() -> service.assertCanCreateRepository(8L, "acme-org"));
        verifyNoInteractions(restTemplate);
    }

    private static String rsaPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }

    private static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
