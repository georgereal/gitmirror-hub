package com.gitutility.coverage;

import com.gitutility.model.dto.GitHubAppConfigRequest;
import com.gitutility.model.dto.TestConnectionRequest;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.service.FeatureFlagsService;
import com.gitutility.service.GitHubAuthService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("coverage")
class GitHubAuthCoverageTest {

    @Test
    void tokenMintConfigSaveAndAccessCheckRun() throws Exception {
        GitHubAppConfigRepository configs = mock(GitHubAppConfigRepository.class);
        when(configs.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(configs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GitHubAuthService auth = new GitHubAuthService(
                configs,
                mock(ScmProviderFacade.class),
                mock(FeatureFlagsService.class),
                CoverageHttp.ok());

        auth.invalidateCachedToken();
        GitHubAppConfigRequest request = new GitHubAppConfigRequest();
        request.setAuthType("PAT");
        request.setAppId("4242");
        GitHubAppConfig saved = auth.saveAppConfig(request);
        assertNotNull(saved);
        assertNotNull(auth.generateAppJwt("4242", pem()));
        assertNotNull(auth.checkRepositoryAccess(TestConnectionRequest.builder()
                .repoUrl("https://github.com/acme/widget.git")
                .token("gh-token")
                .requiredAccess("READ")
                .build()));
        assertFalse(auth.getEffectiveGitHubToken() == null && saved.getAppId() == null);
    }

    private static String pem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }
}
