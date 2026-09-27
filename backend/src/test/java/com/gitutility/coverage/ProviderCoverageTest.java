package com.gitutility.coverage;

import com.gitutility.model.dto.CreateRepoRequest;
import com.gitutility.model.dto.TestConnectionRequest;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.provider.bitbucket.BitbucketProviderService;
import com.gitutility.provider.ghes.GitHubEnterpriseProviderService;
import com.gitutility.provider.github.GitHubProviderService;
import com.gitutility.provider.github.GithubGraphQlClient;
import com.gitutility.provider.gitlab.GitLabProviderService;
import com.gitutility.provider.origin.OriginProviderService;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.service.FeatureFlagsService;
import com.gitutility.service.ScmCredentialService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("coverage")
class ProviderCoverageTest {

    @Test
    void githubGitlabBitbucketGhesAndOriginAdaptersRun() {
        GitHubAppConfig config = GitHubAppConfig.builder()
                .ghesHostUrl("https://ghes.example")
                .gitlabHostUrl("https://gitlab.com")
                .gitlabAccessToken("gl-token")
                .bitbucketAccessToken("bb-token")
                .originAccessToken("origin-token")
                .build();
        GitHubAppConfigRepository configs = mock(GitHubAppConfigRepository.class);
        when(configs.findFirstByOrderByIdAsc()).thenReturn(Optional.of(config));
        ScmCredentialService credentials = mock(ScmCredentialService.class);
        when(credentials.resolveCurrentOrNull()).thenReturn("gh-token");
        when(credentials.hostMatchesAnyGhes(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        RestTemplate http = CoverageHttp.ok();
        GithubGraphQlClient graph = mock(GithubGraphQlClient.class);

        GitHubProviderService github = new GitHubProviderService(configs, http, graph, credentials);
        ReflectionTestUtils.setField(github, "graphqlEnabled", false);
        GitHubEnterpriseProviderService ghes = new GitHubEnterpriseProviderService(configs, http, graph, credentials);
        ReflectionTestUtils.setField(ghes, "graphqlEnabled", false);
        GitLabProviderService gitlab = new GitLabProviderService(configs, http);
        BitbucketProviderService bitbucket = new BitbucketProviderService(configs, http);
        OriginProviderService origin = new OriginProviderService(configs);

        touch(github, "https://github.com/acme/widget.git", "acme/widget", true);
        touch(ghes, "https://ghes.example/acme/mirror.git", "acme/mirror", true);
        touch(gitlab, "https://gitlab.com/acme/widget.git", "acme/widget", false);
        touch(bitbucket, "https://bitbucket.org/acme/widget.git", "acme/widget", false);
        assertTrue(origin.supportsUrl("https://origin.cursor.com/acme/widget.git"));
        assertEquals("acme/widget", origin.parseRepoFullName("https://origin.cursor.com/acme/widget.git"));
        origin.getGitCredentials("https://origin.cursor.com/acme/widget.git", "origin-token");
        origin.testConnection(TestConnectionRequest.builder()
                .repoUrl("https://origin.cursor.com/acme/widget.git")
                .token("origin-token")
                .requiredAccess("WRITE")
                .build());

        GitHubProviderService missing = new GitHubProviderService(configs, CoverageHttp.notFound(), graph, credentials);
        ReflectionTestUtils.setField(missing, "graphqlEnabled", false);
        assertFalse(missing.repositoryExists("https://github.com/acme/missing.git"));

        ScmProviderFacade facade = new ScmProviderFacade(
                List.of(github, gitlab, bitbucket, ghes, origin),
                mock(FeatureFlagsService.class),
                credentials);
        assertEquals(ScmProviderType.GITHUB, facade.getAdapterForUrl("https://github.com/acme/widget.git").getProviderType());
        assertEquals(ScmProviderType.GITLAB, facade.getAdapterForUrl("https://gitlab.com/acme/widget.git").getProviderType());
        assertEquals(ScmProviderType.BITBUCKET, facade.getAdapterForUrl("https://bitbucket.org/acme/widget.git").getProviderType());
    }

    private static void touch(ScmProviderAdapter adapter, String url, String fullName, boolean rulesets) {
        assertTrue(adapter.supportsUrl(url));
        assertEquals(fullName, adapter.parseRepoFullName(url));
        adapter.invalidateTokenCache();
        adapter.getGitCredentials(url, "token");
        adapter.testConnection(TestConnectionRequest.builder()
                .repoUrl(url)
                .token("token")
                .requiredAccess("READ")
                .build());
        adapter.repositoryExists(url);
        adapter.hasCommits(url);
        adapter.listOpenPullRequests(fullName);
        adapter.createPullRequest(fullName, "Change", "notes", "feature", "main");
        adapter.closePullRequest(fullName, 1);
        adapter.listReleases(fullName);
        adapter.replicateCommitStatus(fullName, "abc", "success", "https://ci.example/1", "ok", "tests");
        adapter.replicateComments(fullName, 1, List.of("note"));
        adapter.searchRepositories("widget", 1, 5);
        adapter.createRemoteRepository(CreateRepoRequest.builder()
                .name("widget")
                .owner("acme")
                .visibility("private")
                .build());
        if (rulesets) {
            adapter.ensureReplicaReadonlyRuleset(fullName, 4242L, "active");
            adapter.listRepositoryRulesets(fullName);
        }
    }
}
