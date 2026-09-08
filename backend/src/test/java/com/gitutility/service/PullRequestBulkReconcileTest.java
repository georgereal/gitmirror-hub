package com.gitutility.service;

import com.gitutility.model.dto.PrListPage;
import com.gitutility.model.dto.PullRequestSnapshot;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.PairSide;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PullRequestBulkReconcileTest {

    @Mock private PrMappingRepository prMappingRepository;
    @Mock private ScmProviderFacade scmProviderFacade;
    @Mock private RepoMappingRepository repoMappingRepository;
    @Mock private StorageTieringService storageTieringService;
    @Mock private DedupLedgerService dedupLedgerService;
    @Mock private SyncConflictService syncConflictService;
    @Mock private JobExecutionStateService jobExecutionStateService;
    @Mock private JobCancellationService jobCancellationService;
    @Mock private PairDiffSnapshotService pairDiffSnapshotService;
    @Mock private ActionsTriggerSuppressionService actionsTriggerSuppressionService;
    @Mock private PairCatchupLedger pairCatchupLedger;
    @Mock private ScmProviderAdapter githubAdapter;

    private PullRequestSyncService service;

    @BeforeEach
    void setUp() {
        service = new PullRequestSyncService(
                prMappingRepository,
                scmProviderFacade,
                repoMappingRepository,
                storageTieringService,
                dedupLedgerService,
                syncConflictService,
                jobExecutionStateService,
                jobCancellationService,
                pairDiffSnapshotService,
                actionsTriggerSuppressionService,
                pairCatchupLedger,
                null,
                Executors.newFixedThreadPool(2)
        );
        ReflectionTestUtils.setField(service, "prForkLazyMaterialize", true);
        ReflectionTestUtils.setField(service, "prForkPushObjectRefs", true);
        ReflectionTestUtils.setField(service, "prForkFetchBatchSize", 32);
        ReflectionTestUtils.setField(service, "prListPageSize", 100);
        ReflectionTestUtils.setField(service, "prSkipExistingHeads", true);
        ReflectionTestUtils.setField(service, "prHeadPrepProgressInterval", 1);
        lenient().when(scmProviderFacade.parseRepoFullName(contains("microsoft/vscode")))
                .thenReturn("microsoft/vscode");
        lenient().when(scmProviderFacade.parseRepoFullName(contains("mirror_vscode")))
                .thenReturn("georgereal/mirror_vscode");
        lenient().when(scmProviderFacade.getAdapterForUrl(any())).thenReturn(githubAdapter);
        lenient().when(storageTieringService.resolveRepoDirectory(anyLong(), any())).thenReturn(null);
        lenient().when(jobExecutionStateService.loadStageProgressByJobId(any())).thenReturn(
                com.gitutility.model.dto.JobStageProgress.empty());
    }

    private RepoMapping pair() {
        return RepoMapping.builder()
                .id(5L)
                .repoAUrl("https://github.com/microsoft/vscode")
                .repoBUrl("https://github.com/georgereal/mirror_vscode")
                .build();
    }

    @Test
    void bulkSyncDeltaModeUsesRecentlyClosedAndRecordsWatermark() {
        String mirrorBody = PrMirrorSupport.buildMirroredBody(
                "microsoft/vscode", 100L, "alice", "https://github.com/microsoft/vscode/pull/100", "hello");
        PrMapping existing = PrMapping.builder()
                .id(1L)
                .mappingId(5L)
                .sourcePrNumber(100L)
                .targetPrNumber(9L)
                .headBranch("feature")
                .baseBranch("main")
                .title("Same title")
                .state("open")
                .originSide(PairSide.A.name())
                .lastPushedTitle("Same title")
                .lastPushedBody(mirrorBody)
                .lastPushedAt(Instant.now())
                .build();

        RepoMapping mapping = pair();
        mapping.setLastPrListCompletedAt(Instant.parse("2026-09-07T12:00:00Z"));
        when(repoMappingRepository.findById(5L)).thenReturn(Optional.of(mapping));
        when(prMappingRepository.findByMappingId(5L)).thenReturn(List.of(existing));
        when(githubAdapter.listOpenPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt()))
                .thenReturn(new PrListPage(List.of(
                        SyncDiffReport.PrSyncDetail.builder()
                                .sourcePrNumber(100L)
                                .title("Same title")
                                .body("hello")
                                .headBranch("feature")
                                .baseBranch("main")
                                .authorLogin("alice")
                                .sourcePrUrl("https://github.com/microsoft/vscode/pull/100")
                                .isFork(false)
                                .updatedAt(Instant.parse("2026-09-07T11:50:00Z"))
                                .build()
                ), "NEXT", true, 2500, true));
        when(githubAdapter.listRecentlyClosedPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt()))
                .thenReturn(PrListPage.empty());

        int result = service.syncOpenPullRequests(5L,
                "https://github.com/microsoft/vscode",
                "https://github.com/georgereal/mirror_vscode");

        assertEquals(0, result);
        // Older-than-watermark page stops the crawl — no second open page fetch.
        verify(githubAdapter, times(1)).listOpenPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt());
        verify(githubAdapter, never()).listOpenPullRequestsPage(eq("microsoft/vscode"), eq("NEXT"), anyInt());
        verify(githubAdapter).listRecentlyClosedPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt());
        verify(pairCatchupLedger).recordPrListCompleted(5L);
    }

    @Test
    void bulkSyncSkipsDestApiWhenMappedMetadataUnchanged() {
        String mirrorBody = PrMirrorSupport.buildMirroredBody(
                "microsoft/vscode", 100L, "alice", "https://github.com/microsoft/vscode/pull/100", "hello");
        PrMapping existing = PrMapping.builder()
                .id(1L)
                .mappingId(5L)
                .sourcePrNumber(100L)
                .targetPrNumber(9L)
                .headBranch("feature")
                .baseBranch("main")
                .title("Same title")
                .state("open")
                .originSide(PairSide.A.name())
                .lastPushedTitle("Same title")
                .lastPushedBody(mirrorBody)
                .lastPushedAt(Instant.now())
                .build();

        when(repoMappingRepository.findById(5L)).thenReturn(Optional.of(pair()));
        when(prMappingRepository.findByMappingId(5L)).thenReturn(List.of(existing));
        when(githubAdapter.listOpenPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt()))
                .thenReturn(new PrListPage(List.of(
                        SyncDiffReport.PrSyncDetail.builder()
                                .sourcePrNumber(100L)
                                .title("Same title")
                                .body("hello")
                                .headBranch("feature")
                                .baseBranch("main")
                                .authorLogin("alice")
                                .sourcePrUrl("https://github.com/microsoft/vscode/pull/100")
                                .isFork(false)
                                .build()
                ), null, false, 1));

        int result = service.syncOpenPullRequests(5L,
                "https://github.com/microsoft/vscode",
                "https://github.com/georgereal/mirror_vscode");

        assertEquals(0, result);
        verify(githubAdapter, never()).getPullRequest(anyString(), anyLong());
        verify(githubAdapter, never()).updatePullRequest(anyString(), anyLong(), anyString(), anyString());
        verify(githubAdapter, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(githubAdapter, never()).closePullRequest(anyString(), anyLong());
        verify(pairCatchupLedger).recordPrListCompleted(5L);
    }

    @Test
    void bulkSyncPatchesReplicaWhenOriginTitleDrifted() {
        String oldBody = PrMirrorSupport.buildMirroredBody(
                "microsoft/vscode", 100L, "alice", "https://github.com/microsoft/vscode/pull/100", "old");
        PrMapping existing = PrMapping.builder()
                .id(1L)
                .mappingId(5L)
                .sourcePrNumber(100L)
                .targetPrNumber(9L)
                .headBranch("feature")
                .baseBranch("main")
                .title("Old title")
                .state("open")
                .originSide(PairSide.A.name())
                .lastPushedTitle("Old title")
                .lastPushedBody(oldBody)
                .lastPushedAt(Instant.now())
                .build();

        when(repoMappingRepository.findById(5L)).thenReturn(Optional.of(pair()));
        when(prMappingRepository.findByMappingId(5L)).thenReturn(List.of(existing));
        when(githubAdapter.listOpenPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt()))
                .thenReturn(new PrListPage(List.of(
                        SyncDiffReport.PrSyncDetail.builder()
                                .sourcePrNumber(100L)
                                .title("New title")
                                .body("old")
                                .headBranch("feature")
                                .baseBranch("main")
                                .authorLogin("alice")
                                .sourcePrUrl("https://github.com/microsoft/vscode/pull/100")
                                .isFork(false)
                                .build()
                ), null, false, 1));
        when(githubAdapter.getPullRequest("georgereal/mirror_vscode", 9L))
                .thenReturn(PullRequestSnapshot.builder()
                        .title("Old title")
                        .body(oldBody)
                        .build());
        when(githubAdapter.updatePullRequest(eq("georgereal/mirror_vscode"), eq(9L), eq("New title"), anyString()))
                .thenReturn(true);
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));

        int result = service.syncOpenPullRequests(5L,
                "https://github.com/microsoft/vscode",
                "https://github.com/georgereal/mirror_vscode");

        assertEquals(1, result);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(githubAdapter).updatePullRequest(eq("georgereal/mirror_vscode"), eq(9L), eq("New title"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("old"));
        verify(githubAdapter, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bulkSyncClosesReplicaWhenOriginNoLongerOpen() {
        PrMapping existing = PrMapping.builder()
                .id(1L)
                .mappingId(5L)
                .sourcePrNumber(100L)
                .targetPrNumber(9L)
                .headBranch("feature")
                .baseBranch("main")
                .title("Gone")
                .state("open")
                .originSide(PairSide.A.name())
                .lastPushedTitle("Gone")
                .lastPushedBody("body")
                .build();

        when(repoMappingRepository.findById(5L)).thenReturn(Optional.of(pair()));
        when(prMappingRepository.findByMappingId(5L)).thenReturn(List.of(existing));
        when(prMappingRepository.findByMappingIdAndSourcePrNumber(5L, 100L)).thenReturn(Optional.of(existing));
        when(githubAdapter.listOpenPullRequestsPage(eq("microsoft/vscode"), isNull(), anyInt()))
                .thenReturn(new PrListPage(List.of(), null, false, 0));
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));

        int result = service.syncOpenPullRequests(5L,
                "https://github.com/microsoft/vscode",
                "https://github.com/georgereal/mirror_vscode");

        assertEquals(1, result);
        verify(githubAdapter).closePullRequest("georgereal/mirror_vscode", 9L);
        assertEquals("closed", existing.getState());
    }
}
