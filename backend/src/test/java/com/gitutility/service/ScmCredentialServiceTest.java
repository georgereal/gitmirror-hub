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

    private ScmCredentialService service;

    @BeforeEach
    void setUp() {
        service = new ScmCredentialService(
                credentialRepository, legacyConfigRepository, mappingRepository, restTemplate);
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
        assertTrue(ex.getMessage().contains("sourceCredentialId"));
    }

    @Test
    void ownerMismatchThrowsInstallationMismatch() {
        ScmCredential cred = ScmCredential.builder()
                .label("old-org")
                .provider("GITHUB")
                .hostUrl("https://github.com")
                .authMode("PERSONAL_ACCESS_TOKEN")
                .patToken("ghp_x")
                .accountLogin("old-org")
                .enabled(true)
                .build();
        AuthInstallationMismatchException mismatch = assertThrows(AuthInstallationMismatchException.class,
                () -> service.assertRepoAccessible(cred, "https://github.com/new-org/repo.git"));
        assertTrue(mismatch.getMessage().contains(AuthInstallationMismatchException.CODE));
    }

    private static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
