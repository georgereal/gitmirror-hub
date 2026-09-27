package com.gitutility.service;

import com.gitutility.model.dto.JobStageProgress;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.repository.SyncJobRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Locks an incremental {@code refs/heads/main} fetch and push against local repositories.
 */
@ExtendWith(MockitoExtension.class)
class GitSyncEngineLocalMirrorTest {

    @Mock SyncAuditLogRepository auditLogRepository;
    @Mock DedupLedgerService dedupLedgerService;
    @Mock GitLfsSyncService gitLfsSyncService;
    @Mock StorageTieringService storageTieringService;
    @Mock RepoMappingRepository repoMappingRepository;
    @Mock EnterpriseLoggingService enterpriseLoggingService;
    @Mock WebSocketNotificationService webSocketNotificationService;
    @Mock ProviderRateMeter providerRateMeter;
    @Mock SyncJobRepository syncJobRepository;
    @Mock JobCancellationService jobCancellationService;
    @Mock RefOriginService refOriginService;
    @Mock SyncCheckpointService syncCheckpointService;
    @Mock JobExecutionStateService jobExecutionStateService;

    private GitSyncEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GitSyncEngine(
                auditLogRepository,
                dedupLedgerService,
                gitLfsSyncService,
                storageTieringService,
                repoMappingRepository,
                enterpriseLoggingService,
                null,
                webSocketNotificationService,
                providerRateMeter,
                syncJobRepository,
                jobCancellationService,
                refOriginService,
                null,
                null,
                syncCheckpointService,
                jobExecutionStateService,
                null,
                null,
                null,
                null,
                null);
        lenient().when(jobExecutionStateService.shouldResume(any())).thenReturn(false);
        lenient().when(jobExecutionStateService.loadPipeline(any())).thenReturn(SyncPipelineState.initial());
        lenient().when(jobExecutionStateService.loadStageProgress(any())).thenReturn(JobStageProgress.empty());
        lenient().when(jobExecutionStateService.isMetadataPhase(any())).thenReturn(false);
        lenient().when(storageTieringService.resolveRepoDirectory(any(), any())).thenReturn(null);
    }

    @Test
    void incrementalMainFetchesOnlyThatRefAndLandsOnTheTarget(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Path targetDir = tempDir.resolve("target.git");
        ObjectId sourceTip;
        try (Git source = Git.init().setDirectory(sourceDir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(sourceDir.resolve("README"), "hello");
            source.add().addFilepattern("README").call();
            source.commit()
                    .setMessage("init")
                    .setAuthor("Dev Tester", "dev@example.com")
                    .setCommitter("Dev Tester", "dev@example.com")
                    .call();
            sourceTip = source.getRepository().resolve("refs/heads/main");
        }
        try (Git ignored = Git.init().setBare(true).setDirectory(targetDir.toFile()).call()) {
            // empty destination
        }

        SyncEventMessage event = SyncEventMessage.builder()
                .jobId("job-local")
                .mappingId("local-inc")
                .pairName("local")
                .sourceRepoUrl(sourceDir.toUri().toString())
                .targetRepoUrl(targetDir.toUri().toString())
                .branch("main")
                .ref("refs/heads/main")
                .afterSha(sourceTip.getName())
                .build();

        RefSpec[] specs = GitSyncEngine.sourceFetchRefSpecs(event);
        assertEquals(1, specs.length);
        assertEquals("+refs/heads/main:refs/heads/main", specs[0].toString());
        RefSpec[] fullMirror = GitSyncEngine.sourceFetchRefSpecs(
                SyncEventMessage.builder().branch("*").build(), true);
        assertTrue(Arrays.stream(fullMirror).anyMatch(spec -> spec.toString().contains("refs/pull")));
        assertTrue(fullMirror.length > 1);

        ReflectionTestUtils.setField(engine, "workspaceDir", tempDir.resolve("cache").toString());
        ReflectionTestUtils.setField(engine, "httpTimeoutSeconds", 60);
        ReflectionTestUtils.setField(engine, "pushBatchSize", 8);
        ReflectionTestUtils.setField(engine, "pushBatchRetries", 1);
        ReflectionTestUtils.setField(engine, "pushBulkBootstrap", false);
        ReflectionTestUtils.setField(engine, "httpPostBufferBytes", 1024 * 1024);

        GitSyncEngine.SyncResult result = engine.executeSync(event);
        assertTrue(result.success, result.message);

        try (Git target = Git.open(targetDir.toFile())) {
            ObjectId targetTip = target.getRepository().resolve("refs/heads/main");
            assertEquals(sourceTip, targetTip);
        }
    }
}
