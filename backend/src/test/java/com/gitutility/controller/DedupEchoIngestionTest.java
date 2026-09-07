package com.gitutility.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.DedupLedgerService;
import com.gitutility.service.PullRequestSyncService;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.RefInterestPolicy;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.ReleaseAndStatusSyncService;
import com.gitutility.service.WebhookIngestionService;
import com.gitutility.service.WebSocketNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DedupEchoIngestionTest {

    @Mock
    private RepoMappingRepository mappingRepository;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private QueueProducerService queueProducerService;
    @Mock
    private WebSocketNotificationService webSocketNotificationService;
    @Mock
    private PullRequestSyncService pullRequestSyncService;
    @Mock
    private ReleaseAndStatusSyncService releaseAndStatusSyncService;
    @Mock
    private UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    @Mock
    private RefOriginService refOriginService;

    private DedupLedgerService dedupLedgerService;
    private WebhookController webhookController;

    private static final String DEST_NO_GIT = "https://github.com/acme/mirror-dest";
    private static final String DEST_WITH_GIT = "https://github.com/acme/mirror-dest.git";
    private static final String SOURCE = "https://github.com/microsoft/vscode";
    private static final String PR_HEAD_SHA = "388dc77aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach
    void setUp() {
        dedupLedgerService = new DedupLedgerService();
        ReflectionTestUtils.setField(dedupLedgerService, "ledgerTtlSeconds", 600L);

        WebhookIngestionService ingestion = new WebhookIngestionService(
                mappingRepository,
                syncJobRepository,
                queueProducerService,
                dedupLedgerService,
                webSocketNotificationService,
                new ObjectMapper(),
                pullRequestSyncService,
                releaseAndStatusSyncService,
                unmappedWebhookEventRepository,
                refOriginService,
                new RefInterestPolicy("agents/,dependabot/", 45_000L, true)
        );
        webhookController = new WebhookController(mappingRepository, ingestion, new ObjectMapper(), null);
    }

    @Test
    void prSyncRecordedShaSkipsDestWebhookEvenWhenCloneUrlAddsGitSuffix() {
        dedupLedgerService.recordSystemPush(DEST_NO_GIT, PR_HEAD_SHA);

        RepoMapping mapping = vscodePair();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                1L, "push", null, destPushPayload(PR_HEAD_SHA, "feat/rag-workflow", "feat: rag workflow")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("skipped", body.get("status"));

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(jobCaptor.capture());
        assertEquals(SyncStatus.SKIPPED, jobCaptor.getValue().getStatus());
        assertEquals("LOOP_DETECTED_SYSTEM_ECHO", jobCaptor.getValue().getSkipReason());
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void gitSyncBatchShasSkipDestWebhooksForEveryPushedTip() {
        dedupLedgerService.recordSystemPush(DEST_NO_GIT, "aaa111");
        dedupLedgerService.recordSystemPush(DEST_NO_GIT, "bbb222");

        RepoMapping mapping = vscodePair();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> first = webhookController.handleMappingSpecificWebhook(
                1L, "push", null, destPushPayload("aaa111", "feat/rag-workflow", "feat: rag"));
        ResponseEntity<?> second = webhookController.handleMappingSpecificWebhook(
                1L, "push", null, destPushPayload("bbb222", "fix/importer-color-cache-key", "fix: cache"));

        assertEquals("skipped", ((Map<?, ?>) first.getBody()).get("status"));
        assertEquals("skipped", ((Map<?, ?>) second.getBody()).get("status"));
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void unrecordedSourceWebhookStillEnqueuesAndUsesHeadCommitMessage() {
        dedupLedgerService.recordSystemPush(DEST_NO_GIT, PR_HEAD_SHA);

        RepoMapping mapping = vscodePair();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> {
            SyncJob j = i.getArgument(0);
            j.setId(42L);
            return j;
        });

        String sourceSha = "sourcecommit111111111111111111111111111";
        String payload = """
                {
                  "ref": "refs/heads/main",
                  "after": "%s",
                  "repository": {
                    "clone_url": "%s.git",
                    "full_name": "microsoft/vscode"
                  },
                  "head_commit": {
                    "id": "%s",
                    "message": "feat: real commit subject"
                  },
                  "pusher": { "name": "developer" }
                }
                """.formatted(sourceSha, SOURCE, sourceSha);

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(1L, "push", null, payload);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(queueProducerService).enqueueSyncJob(
                eq(mapping), any(SyncJob.class), any(), any(), eq("refs/heads/main"), eq("main"),
                any(), eq(sourceSha), eq("feat: real commit subject"), any(), any()
        );
    }

    @Test
    void destSyntheticForkHeadIsSkippedOnReverseWebhook() {
        RepoMapping mapping = RepoMapping.builder()
                .id(1L)
                .name("OpenMAIC")
                .repoAUrl("https://github.com/THU-MAIC/OpenMAIC")
                .repoBUrl(DEST_NO_GIT)
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .build();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> i.getArgument(0));
        when(refOriginService.isForkPrHead(1L, "add-repocloud-deploy-button")).thenReturn(true);

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                1L, "push", null,
                destPushPayload("388dc77aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "add-repocloud-deploy-button", "fork head")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("skipped", body.get("status"));
        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(jobCaptor.capture());
        assertEquals(SyncStatus.SKIPPED, jobCaptor.getValue().getStatus());
        assertEquals("DEST_SYNTHETIC_FORK_HEAD", jobCaptor.getValue().getSkipReason());
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void originDeleteIsEnqueuedAndReplicaDeleteIsSkipped() {
        RepoMapping mapping = RepoMapping.builder()
                .id(1L)
                .name("OpenMAIC")
                .repoAUrl("https://github.com/THU-MAIC/OpenMAIC.git")
                .repoBUrl(DEST_NO_GIT)
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .build();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> {
            SyncJob j = i.getArgument(0);
            j.setId(99L);
            return j;
        });

        String zeros = "0000000000000000000000000000000000000000";
        String originDelete = """
                {
                  "ref": "refs/heads/feature-x",
                  "after": "%s",
                  "deleted": true,
                  "repository": {
                    "clone_url": "https://github.com/THU-MAIC/OpenMAIC.git",
                    "full_name": "THU-MAIC/OpenMAIC"
                  },
                  "pusher": { "name": "developer" }
                }
                """.formatted(zeros);

        ResponseEntity<?> origin = webhookController.handleMappingSpecificWebhook(1L, "push", null, originDelete);
        assertEquals(HttpStatus.ACCEPTED, origin.getStatusCode());
        verify(queueProducerService).enqueueSyncJob(
                eq(mapping), any(SyncJob.class), any(), any(), eq("refs/heads/feature-x"), eq("feature-x"),
                any(), eq(zeros), eq("Deleted branch feature-x"), any(), any()
        );

        when(refOriginService.isReplicaEvent(eq(1L), eq("refs/heads/feature-x"), any())).thenReturn(true);
        String replicaDelete = """
                {
                  "ref": "refs/heads/feature-x",
                  "after": "%s",
                  "deleted": true,
                  "repository": {
                    "clone_url": "%s",
                    "full_name": "acme/mirror-dest"
                  },
                  "pusher": { "name": "someone" }
                }
                """.formatted(zeros, DEST_WITH_GIT);

        ResponseEntity<?> replica = webhookController.handleMappingSpecificWebhook(1L, "push", null, replicaDelete);
        assertEquals(HttpStatus.OK, replica.getStatusCode());
        ArgumentCaptor<SyncJob> jobs = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository, times(2)).save(jobs.capture());
        assertEquals("REPLICA_REF_DELETE", jobs.getAllValues().get(1).getSkipReason());
    }

    @Test
    void dependabotBranchOnMirrorIsSkipped() {
        RepoMapping mapping = backupVscodePair();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                1L, "push", null,
                destPushPayload("9e42f862f4fbcfa92a3b700e71c7238544a6cdd5",
                        "dependabot/github_actions/actions/cache/save-6.1.0", "bump actions/cache")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(jobCaptor.capture());
        assertEquals(SyncStatus.SKIPPED, jobCaptor.getValue().getStatus());
        // dependabot/ is in ephemeral prefixes — live webhook gate wins over REPLICA_BOT_BRANCH
        assertEquals("EPHEMERAL_REF_IGNORED", jobCaptor.getValue().getSkipReason());
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void replicaBotBranchSkippedWhenNotInEphemeralList() {
        dedupLedgerService = new DedupLedgerService();
        ReflectionTestUtils.setField(dedupLedgerService, "ledgerTtlSeconds", 600L);
        WebhookIngestionService ingestion = new WebhookIngestionService(
                mappingRepository,
                syncJobRepository,
                queueProducerService,
                dedupLedgerService,
                webSocketNotificationService,
                new ObjectMapper(),
                pullRequestSyncService,
                releaseAndStatusSyncService,
                unmappedWebhookEventRepository,
                refOriginService,
                new RefInterestPolicy("agents/", 45_000L, true)
        );
        webhookController = new WebhookController(mappingRepository, ingestion, new ObjectMapper(), null);

        RepoMapping mapping = backupVscodePair();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                1L, "push", null,
                destPushPayload("9e42f862f4fbcfa92a3b700e71c7238544a6cdd5",
                        "dependabot/github_actions/actions/cache/save-6.1.0", "bump actions/cache")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(jobCaptor.capture());
        assertEquals(SyncStatus.SKIPPED, jobCaptor.getValue().getStatus());
        assertEquals("REPLICA_BOT_BRANCH", jobCaptor.getValue().getSkipReason());
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void publicPrivateBackupSkipsUnknownOriginMirrorWebhook() {
        RepoMapping mapping = backupVscodePair();
        when(mappingRepository.findById(1L)).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> i.getArgument(0));
        when(refOriginService.shouldBlockReplicaInboundWebhook(eq(mapping), eq("refs/heads/feature-local"), any()))
                .thenReturn(true);

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                1L, "push", null,
                destPushPayload("abc123def4567890123456789012345678901234", "feature-local", "local mirror work")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(jobCaptor.capture());
        assertEquals(SyncStatus.SKIPPED, jobCaptor.getValue().getStatus());
        assertEquals("REPLICA_BACKUP_MIRROR", jobCaptor.getValue().getSkipReason());
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    private static RepoMapping backupVscodePair() {
        return RepoMapping.builder()
                .id(1L)
                .name("vscode")
                .repoAUrl(SOURCE)
                .repoBUrl(DEST_NO_GIT)
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .sourceVisibility(com.gitutility.model.enums.RepoVisibility.PUBLIC)
                .targetVisibility(com.gitutility.model.enums.RepoVisibility.PRIVATE)
                .build();
    }

    private static RepoMapping vscodePair() {
        return RepoMapping.builder()
                .id(1L)
                .name("vscode")
                .repoAUrl(SOURCE)
                .repoBUrl(DEST_NO_GIT)
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .build();
    }

    private static String destPushPayload(String sha, String branch, String message) {
        return """
                {
                  "ref": "refs/heads/%s",
                  "after": "%s",
                  "repository": {
                    "clone_url": "%s",
                    "full_name": "acme/mirror-dest"
                  },
                  "head_commit": {
                    "id": "%s",
                    "message": "%s"
                  },
                  "pusher": { "name": "gitmirror[bot]" }
                }
                """.formatted(branch, sha, DEST_WITH_GIT, sha, message);
    }
}
