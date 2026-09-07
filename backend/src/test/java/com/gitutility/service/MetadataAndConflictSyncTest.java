package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MetadataAndConflictSyncTest {

    @Mock
    private SyncAuditLogRepository auditLogRepository;
    @Mock
    private DedupLedgerService dedupLedgerService;
    @Mock
    private GitHubAppConfigRepository gitHubAppConfigRepository;
    @Mock
    private GitHubAuthService gitHubAuthService;
    @Mock
    private GitLfsSyncService gitLfsSyncService;
    @Mock
    private StorageTieringService storageTieringService;
    @Mock
    private com.gitutility.repository.RepoMappingRepository repoMappingRepository;
    @Mock
    private EnterpriseLoggingService enterpriseLoggingService;
    @Mock
    private WebSocketNotificationService webSocketNotificationService;
    @Mock
    private ProviderRateMeter providerRateMeter;
    @Mock
    private com.gitutility.repository.SyncJobRepository syncJobRepository;
    @Mock
    private com.gitutility.provider.ScmProviderFacade scmProviderFacade;
    @Mock
    private JobCancellationService jobCancellationService;
    @Mock
    private RefOriginService refOriginService;
    @Mock
    private SyncCheckpointService syncCheckpointService;
    @Mock
    private JobExecutionStateService jobExecutionStateService;

    @InjectMocks
    private GitSyncEngine gitSyncEngine;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(gitSyncEngine, "workspaceDir", "/tmp/git-utility-mirrors");
        ReflectionTestUtils.setField(gitSyncEngine, "httpTimeoutSeconds", 600);
        ReflectionTestUtils.setField(gitSyncEngine, "pushBatchSize", 8);
        ReflectionTestUtils.setField(gitSyncEngine, "pushBatchRetries", 3);
        ReflectionTestUtils.setField(gitSyncEngine, "httpPostBufferBytes", 524288000);
        org.mockito.Mockito.lenient().when(storageTieringService.resolveRepoDirectory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.io.File("/tmp/git-utility-mirrors/pair-1.git"));
        org.mockito.Mockito.lenient().when(syncCheckpointService.getStage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.gitutility.model.enums.SyncCheckpointStage.NONE);
        org.mockito.Mockito.lenient().when(syncCheckpointService.resolveResumeStage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.gitutility.model.enums.SyncCheckpointStage.NONE);
        org.mockito.Mockito.lenient().when(jobExecutionStateService.shouldResume(org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        org.mockito.Mockito.lenient().when(jobExecutionStateService.loadPipeline(org.mockito.ArgumentMatchers.any()))
                .thenReturn(SyncPipelineState.initial());
        org.mockito.Mockito.lenient().when(jobExecutionStateService.loadStageProgress(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.gitutility.model.dto.JobStageProgress.empty());
        org.mockito.Mockito.lenient().when(jobExecutionStateService.isMetadataPhase(org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        org.mockito.Mockito.lenient().when(jobExecutionStateService.shouldExecuteStage(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(jobExecutionStateService.resolveResumeStageId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(SyncPipelineState.FAST_PATH);
        org.mockito.Mockito.lenient().when(syncCheckpointService.loadCompletedLfsOids(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of());
        org.mockito.Mockito.lenient().when(syncCheckpointService.loadDiscoveredLfs(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
    }

    @Test
    void testSimulationSyncSucceeds() throws Exception {
        SyncEventMessage event = SyncEventMessage.builder()
                .jobId(1L)
                .mappingId(1L)
                .pairName("demo-pair")
                .sourceRepoUrl("https://example.com/source.git")
                .targetRepoUrl("https://example.com/target.git")
                .branch("main")
                .ref("refs/heads/main")
                .build();

        GitSyncEngine.SyncResult result = gitSyncEngine.executeSync(event);

        assertNotNull(result);
        assertTrue(result.success);
    }
}
