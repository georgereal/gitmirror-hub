package com.gitutility.service;

import com.gitutility.model.dto.ReleaseLookup;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.PrMappingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Locks pull-request head placement and one release per tag once the real services run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PullRequestAndReleaseReplicationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock PrMappingRepository prMappingRepository;
    @Mock ScmProviderFacade scmProviderFacade;
    @Mock StorageTieringService storageTieringService;
    @Mock ScmProviderAdapter pullRequests;

    private PullRequestSyncService pullRequestsService;
    private ExecutorService releasePool;

    @BeforeEach
    void setUp() {
        pullRequestsService = new PullRequestSyncService(
                prMappingRepository,
                scmProviderFacade,
                null,
                storageTieringService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        ReflectionTestUtils.setField(pullRequestsService, "prForkLazyMaterialize", false);
        when(storageTieringService.resolveRepoDirectory(any(), any()))
                .thenReturn(new File("build/missing-pr-mirror.git"));
        when(scmProviderFacade.parseRepoFullName(org.mockito.ArgumentMatchers.contains("origin"))).thenReturn("acme/origin");
        when(scmProviderFacade.parseRepoFullName(org.mockito.ArgumentMatchers.contains("mirror"))).thenReturn("acme/mirror");
        when(scmProviderFacade.getAdapterForUrl(any())).thenReturn(pullRequests);
        when(pullRequests.createPullRequest(any(), any(), any(), any(), any())).thenReturn(4L);
        releasePool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        releasePool.shutdownNow();
    }

    @Test
    void forkAndTrunkHeadsAreCreatedOffMain() throws Exception {
        RepoMapping mapping = pair();
        pullRequestsService.handlePrWebhookEvent(mapping, "opened", pr(7, "feature/widget", "other/fork"));
        verify(pullRequests).createPullRequest(eq("acme/mirror"), any(), any(), eq("fork-pr-7"), eq("main"));

        pullRequestsService.handlePrWebhookEvent(mapping, "opened", pr(8, "main", "acme/origin"));
        verify(pullRequests).createPullRequest(eq("acme/mirror"), any(), any(), eq("fork-pr-8"), eq("main"));
    }

    @Test
    void sameRepositoryFeatureBranchKeepsItsName() throws Exception {
        pullRequestsService.handlePrWebhookEvent(pair(), "opened", pr(9, "feature/widget", "acme/origin"));
        verify(pullRequests).createPullRequest(eq("acme/mirror"), any(), any(), eq("feature/widget"), eq("main"));
    }

    @Test
    void missingReleaseTagIsCreatedOnce() throws Exception {
        ScmProviderAdapter releases = releases(ReleaseLookup.missing());
        releaseService(releases).mirrorWebhookRelease(pair(), null, "published", release("v1", "Ship"));
        verify(releases).createRelease(eq("acme/mirror"), eq("v1"), any(), any(), anyBoolean(), anyBoolean());
        verify(releases, never()).updateRelease(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void existingReleaseTagIsUpdated() throws Exception {
        ScmProviderAdapter releases = releases(new ReleaseLookup(
                true, "42", "v1", "Old name", "old", false, false, List.of()));
        releaseService(releases).mirrorWebhookRelease(pair(), null, "published", release("v1", "Ship"));
        verify(releases).updateRelease(eq("acme/mirror"), eq("42"), eq("v1"), any(), any(), anyBoolean(), anyBoolean());
        verify(releases, never()).createRelease(any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    private ReleaseAndStatusSyncService releaseService(ScmProviderAdapter releases) {
        when(scmProviderFacade.getAdapterForUrl(any())).thenReturn(releases);
        return new ReleaseAndStatusSyncService(
                scmProviderFacade,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                releasePool,
                releasePool);
    }

    private static ScmProviderAdapter releases(ReleaseLookup lookup) {
        ScmProviderAdapter adapter = mock(ScmProviderAdapter.class, withSettings().defaultAnswer(org.mockito.Answers.CALLS_REAL_METHODS));
        when(adapter.getProviderType()).thenReturn(ScmProviderType.GITHUB);
        when(adapter.supportsReleaseSync()).thenReturn(true);
        when(adapter.listReleases(any())).thenReturn(List.of());
        when(adapter.findReleaseByTag(any(), any())).thenReturn(lookup);
        when(adapter.createRelease(any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn("15");
        when(adapter.updateRelease(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);
        when(adapter.listReleasesPage(any(), any(), anyInt())).thenCallRealMethod();
        return adapter;
    }

    private static RepoMapping pair() {
        return RepoMapping.builder()
                .id("pair-1")
                .repoAUrl("https://github.com/acme/origin.git")
                .repoBUrl("https://github.com/acme/mirror.git")
                .build();
    }

    private static JsonNode pr(int number, String headRef, String headRepo) throws Exception {
        return JSON.readTree("""
                {"number":%d,"title":"Change","body":"notes","merged":false,
                 "head":{"ref":"%s","sha":"abc","repo":{"full_name":"%s"}},
                 "base":{"ref":"main"}}
                """.formatted(number, headRef, headRepo));
    }

    private static JsonNode release(String tag, String name) throws Exception {
        return JSON.readTree("""
                {"tag_name":"%s","name":"%s","body":"notes","draft":false,"prerelease":false}
                """.formatted(tag, name));
    }
}
