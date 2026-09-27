package com.gitutility.coverage;

import com.gitutility.provider.GitHubRulesetClient;
import com.gitutility.provider.ReadonlyRulesetSpec;
import com.gitutility.provider.github.GithubGraphQlClient;
import com.gitutility.service.ProviderRateMeter;
import com.gitutility.service.ScmInstallationKeyResolver;
import com.gitutility.service.ScmQuotaTracker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("coverage")
class GitHubClientCoverageTest {

    @Test
    void rulesetClientCreatesAndRejectsABlankToken() {
        RestTemplate http = CoverageHttp.ok();
        var mapper = JsonMapper.builder().build();
        long id = GitHubRulesetClient.ensure(
                http, mapper, "https://api.github.com", "token", "acme/widget", 4242L, "active");
        assertTrue(id > 0);
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_REPO, ReadonlyRulesetSpec.TARGET_THIS_REPO,
                "acme/widget", null, null, 4242L, "active");
        assertNotNull(GitHubRulesetClient.lookup(
                http, mapper, "https://api.github.com/repos/acme/widget/rulesets", "token", spec.rulesetName()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                GitHubRulesetClient.ensure(http, mapper, "https://api.github.com", " ", "acme/widget", 4242L, "active"));
    }

    @Test
    void graphQlClientPostsAQuery() {
        GithubGraphQlClient client = new GithubGraphQlClient(
                CoverageHttp.ok(),
                mock(ScmQuotaTracker.class),
                mock(ScmInstallationKeyResolver.class),
                mock(ProviderRateMeter.class));
        assertNotNull(client.execute(
                "https://api.github.com/graphql", "token", "query { viewer { login } }", Map.of("owner", "acme")));
        assertNull(client.execute(" ", "token", "query", Map.of()));
        client.fetchOpenPullRequestsPage("https://api.github.com/graphql", "token", "acme/widget", null, 10);
        client.fetchReleases("https://api.github.com/graphql", "token", "acme/widget", 5);
    }
}
