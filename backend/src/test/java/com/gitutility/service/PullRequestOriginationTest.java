package com.gitutility.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RepoMapping;
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

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PullRequestOriginationTest {

    @Mock
    private PrMappingRepository prMappingRepository;
    @Mock
    private ScmProviderFacade scmProviderFacade;
    @Mock
    private RepoMappingRepository repoMappingRepository;
    @Mock
    private StorageTieringService storageTieringService;
    @Mock
    private DedupLedgerService dedupLedgerService;
    @Mock
    private SyncConflictService syncConflictService;
    @Mock
    private JobExecutionStateService jobExecutionStateService;
    @Mock
    private JobCancellationService jobCancellationService;
    @Mock
    private PairDiffSnapshotService pairDiffSnapshotService;
    @Mock
    private ActionsTriggerSuppressionService actionsTriggerSuppressionService;
    @Mock
    private PairCatchupLedger pairCatchupLedger;
    @Mock
    private ScmProviderAdapter githubAdapter;

    private PullRequestSyncService service;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        ExecutorService prCreateExecutor = Executors.newFixedThreadPool(2);
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
                prCreateExecutor
        );
        ReflectionTestUtils.setField(service, "prForkLazyMaterialize", true);
        ReflectionTestUtils.setField(service, "prForkPushObjectRefs", true);
        ReflectionTestUtils.setField(service, "prForkFetchBatchSize", 32);
        ReflectionTestUtils.setField(service, "prListPageSize", 100);
        ReflectionTestUtils.setField(service, "prSkipExistingHeads", true);
        ReflectionTestUtils.setField(service, "prHeadPrepProgressInterval", 1);
        lenient().when(scmProviderFacade.parseRepoFullName(contains("OpenMAIC"))).thenReturn("THU-MAIC/OpenMAIC");
        lenient().when(scmProviderFacade.parseRepoFullName(contains("mirror-dest"))).thenReturn("acme/mirror-dest");
        lenient().when(scmProviderFacade.getAdapterForUrl(any())).thenReturn(githubAdapter);
    }

    private RepoMapping pair() {
        return RepoMapping.builder()
                .id(4L)
                .repoAUrl("https://github.com/THU-MAIC/OpenMAIC")
                .repoBUrl("https://github.com/acme/mirror-dest")
                .build();
    }

    @Test
    void originOpenedForkPrCachesObjectsWithoutDestBranchOrPr() {
        when(prMappingRepository.findByMappingIdAndSourcePrNumber(4L, 1287L)).thenReturn(Optional.empty());
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));

        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 1287);
        pr.put("title", "feat: deploy");
        pr.put("body", "");
        pr.putObject("head").put("ref", "add-repocloud-deploy-button")
                .putObject("repo").put("full_name", "some-fork/OpenMAIC");
        pr.putObject("base").put("ref", "main");
        pr.putObject("user").put("login", "alice");
        pr.put("html_url", "https://github.com/THU-MAIC/OpenMAIC/pull/1287");

        service.handlePrWebhookEvent(pair(), "opened", pr, "https://github.com/THU-MAIC/OpenMAIC.git");

        verify(githubAdapter, never()).createPullRequest(anyString(), any(), any(), any(), any());

        ArgumentCaptor<PrMapping> captor = ArgumentCaptor.forClass(PrMapping.class);
        verify(prMappingRepository).save(captor.capture());
        assertTrue(captor.getValue().isForkPrHead());
        assertEquals(PullRequestSyncService.STATE_OBJECTS_CACHED, captor.getValue().getState());
        assertNull(captor.getValue().getTargetPrNumber());
        assertEquals(PullRequestSyncService.hiddenForkObjectRef(1287L), captor.getValue().getHeadBranch());
        assertEquals("A", captor.getValue().getOriginSide());
        assertEquals(1287L, captor.getValue().getSourcePrNumber());
    }

    @Test
    void originOpenedForkPrNamedMainDoesNotCreateDestPrOrTargetMain() {
        when(prMappingRepository.findByMappingIdAndSourcePrNumber(4L, 65L)).thenReturn(Optional.empty());
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));

        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 65);
        pr.put("title", "fork on main");
        pr.put("body", "");
        pr.putObject("head").put("ref", "main")
                .putObject("repo").put("full_name", "someone/OpenMAIC");
        pr.putObject("base").put("ref", "main");

        service.handlePrWebhookEvent(pair(), "opened", pr, "https://github.com/THU-MAIC/OpenMAIC.git");

        verify(githubAdapter, never()).createPullRequest(anyString(), any(), any(), any(), any());
        verify(githubAdapter, never()).createPullRequest(anyString(), any(), any(), eq("main"), any());
        ArgumentCaptor<PrMapping> captor = ArgumentCaptor.forClass(PrMapping.class);
        verify(prMappingRepository).save(captor.capture());
        assertEquals(PullRequestSyncService.STATE_OBJECTS_CACHED, captor.getValue().getState());
        assertNull(captor.getValue().getTargetPrNumber());
    }

    @Test
    void originCloseClosesReplicaAndDoesNotCloseOriginOnReplicaWebhook() {
        PrMapping existing = PrMapping.builder()
                .mappingId(4L)
                .sourcePrNumber(1287L)
                .targetPrNumber(9L)
                .headBranch("add-repocloud-deploy-button")
                .forkPrHead(true)
                .originSide("A")
                .state("open")
                .build();
        when(prMappingRepository.findByMappingIdAndSourcePrNumber(4L, 1287L)).thenReturn(Optional.of(existing));
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));

        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 1287);
        pr.put("title", "feat: deploy");
        pr.putObject("head").put("ref", "add-repocloud-deploy-button");
        pr.putObject("base").put("ref", "main");

        service.handlePrWebhookEvent(pair(), "closed", pr, "https://github.com/THU-MAIC/OpenMAIC.git");

        verify(githubAdapter).closePullRequest("acme/mirror-dest", 9L);
        assertEquals("closed", existing.getState());

        reset(githubAdapter);
        when(prMappingRepository.findByMappingIdAndTargetPrNumber(4L, 9L)).thenReturn(Optional.of(existing));
        ObjectNode destPr = mapper.createObjectNode();
        destPr.put("number", 9);
        destPr.putObject("head").put("ref", "add-repocloud-deploy-button");
        destPr.putObject("base").put("ref", "main");

        service.handlePrWebhookEvent(pair(), "closed", destPr, "https://github.com/acme/mirror-dest.git");

        verify(githubAdapter, never()).closePullRequest(eq("THU-MAIC/OpenMAIC"), anyLong());
    }

    @Test
    void closeActionRecognizesMergedAndDeclined() {
        assertTrue(PullRequestSyncService.isCloseAction("closed"));
        assertTrue(PullRequestSyncService.isCloseAction("merged"));
        assertTrue(PullRequestSyncService.isCloseAction("declined"));
        assertFalse(PullRequestSyncService.isCloseAction("opened"));
        assertTrue(PullRequestSyncService.isOpenAction("opened"));
    }

    @Test
    void replicaHeadBranchNeverUsesExistingTrunkNames() {
        assertEquals("fork-pr-65", PullRequestSyncService.replicaHeadBranch(65, "main", true));
        assertEquals("fork-pr-65", PullRequestSyncService.replicaHeadBranch(65, "refs/heads/main", true));
        assertEquals("fork-pr-12", PullRequestSyncService.replicaHeadBranch(12, "master", false));
        assertEquals("feature-x", PullRequestSyncService.replicaHeadBranch(12, "feature-x", false));
        assertEquals("fork-pr-12", PullRequestSyncService.replicaHeadBranch(12, "feature-x", true));
    }

    @Test
    void originEditPatchesReplicaWhenCasMatches() {
        PrMapping existing = PrMapping.builder()
                .mappingId(4L)
                .sourcePrNumber(1287L)
                .targetPrNumber(9L)
                .originSide("A")
                .lastPushedTitle("old")
                .lastPushedBody("body")
                .build();
        when(prMappingRepository.findByMappingIdAndSourcePrNumber(4L, 1287L)).thenReturn(Optional.of(existing));
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));
        when(githubAdapter.getPullRequest("acme/mirror-dest", 9L))
                .thenReturn(com.gitutility.model.dto.PullRequestSnapshot.builder().title("old").body("body").build());
        when(githubAdapter.updatePullRequest(eq("acme/mirror-dest"), eq(9L), eq("new title"), anyString())).thenReturn(true);

        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 1287);
        pr.put("title", "new title");
        pr.put("body", "new body");
        pr.putObject("head").put("ref", "feat");
        pr.putObject("base").put("ref", "main");

        service.handlePrWebhookEvent(pair(), "edited", pr, "https://github.com/THU-MAIC/OpenMAIC.git");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(githubAdapter).updatePullRequest(eq("acme/mirror-dest"), eq(9L), eq("new title"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("new body"));
        assertTrue(bodyCaptor.getValue().contains(PrMirrorSupport.FOOTER_MARKER));
        assertEquals("new title", existing.getLastPushedTitle());
    }

    @Test
    void originEditRecordsMetadataConflictOnCasMiss() {
        PrMapping existing = PrMapping.builder()
                .mappingId(4L)
                .sourcePrNumber(1287L)
                .targetPrNumber(9L)
                .originSide("A")
                .lastPushedTitle("old")
                .lastPushedBody("body")
                .build();
        when(prMappingRepository.findByMappingIdAndSourcePrNumber(4L, 1287L)).thenReturn(Optional.of(existing));
        when(githubAdapter.getPullRequest("acme/mirror-dest", 9L))
                .thenReturn(com.gitutility.model.dto.PullRequestSnapshot.builder().title("replica edited").body("body").build());

        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 1287);
        pr.put("title", "origin edited");
        pr.put("body", "body");
        pr.putObject("head").put("ref", "feat");
        pr.putObject("base").put("ref", "main");

        service.handlePrWebhookEvent(pair(), "edited", pr, "https://github.com/THU-MAIC/OpenMAIC.git");

        verify(githubAdapter, never()).updatePullRequest(anyString(), anyLong(), any(), any());
        verify(syncConflictService).recordMetadataConflict(eq(4L), isNull(), eq("pr:1287"),
                eq("acme/mirror-dest"), eq("origin edited"), eq("replica edited"), anyString());
    }

    @Test
    void replicaEditDoesNotWriteOrigin() {
        PrMapping existing = PrMapping.builder()
                .mappingId(4L)
                .sourcePrNumber(1287L)
                .targetPrNumber(9L)
                .originSide("A")
                .build();
        when(prMappingRepository.findByMappingIdAndTargetPrNumber(4L, 9L)).thenReturn(Optional.of(existing));

        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 9);
        pr.put("title", "replica title");
        pr.put("body", "x");
        pr.putObject("head").put("ref", "feat");
        pr.putObject("base").put("ref", "main");

        service.handlePrWebhookEvent(pair(), "edited", pr, "https://github.com/acme/mirror-dest.git");

        verify(githubAdapter, never()).updatePullRequest(anyString(), anyLong(), any(), any());
    }

    @Test
    void skipsReverseSyncOfHubConflictPr() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", 44);
        pr.put("title", "[sync-conflict] Merge isolated");
        pr.put("body", "");
        pr.putObject("head").put("ref", "sync-conflict/main-20260101-120000");
        pr.putObject("base").put("ref", "main");

        when(prMappingRepository.findByMappingIdAndTargetPrNumber(4L, 44L)).thenReturn(Optional.empty());

        service.handlePrWebhookEvent(pair(), "opened", pr, "https://github.com/acme/mirror-dest.git");

        verify(githubAdapter, never()).createPullRequest(anyString(), any(), any(), any(), any());
    }
}
