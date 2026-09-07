package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class JobExecutionStateServiceTest {

    @Mock
    private com.gitutility.repository.SyncJobRepository syncJobRepository;

    private JobExecutionStateService service;

    @BeforeEach
    void setUp() {
        service = new JobExecutionStateService(syncJobRepository);
    }

    @Test
    void shouldExecuteStageSkipsSettledStages() {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        pipeline.markDone(SyncPipelineState.FETCH_SOURCE, "done");

        assertFalse(service.shouldExecuteStage(pipeline, SyncPipelineState.PUSH_DEST, SyncPipelineState.FETCH_SOURCE));
        assertFalse(service.shouldExecuteStage(pipeline, SyncPipelineState.FETCH_SOURCE, SyncPipelineState.FETCH_SOURCE));
        assertTrue(service.shouldExecuteStage(pipeline, SyncPipelineState.PUSH_DEST, SyncPipelineState.PUSH_DEST));
    }

    @Test
    void resolveResumeStageIdPrefersCurrentStage() {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        pipeline.markCurrent(SyncPipelineState.PUSH_DEST, "batch 6/49");

        assertEquals(SyncPipelineState.PUSH_DEST, service.resolveResumeStageId(pipeline));
    }

    @Test
    void isMetadataPhaseDetectsPostGitConsumerStages() {
        assertTrue(service.isMetadataPhase(SyncPipelineState.PR_METADATA));
        assertTrue(service.isMetadataPhase(SyncPipelineState.RELEASES));
        assertTrue(service.isMetadataPhase(SyncPipelineState.LFS));
        assertFalse(service.isMetadataPhase(SyncPipelineState.PUSH_DEST));
    }

    @Test
    void skipStageAdvancesResumeCursor() {
        SyncJob job = SyncJob.builder().id(9L).status(SyncStatus.PAUSED).build();
        SyncPipelineState pipeline = SyncPipelineState.initial();
        pipeline.markDone(SyncPipelineState.FETCH_SOURCE, "done");
        pipeline.markCurrent(SyncPipelineState.INSPECT_DEST, "fetching");
        job.setPipelineJson(pipeline.toJson());
        job.setResumeStageId(SyncPipelineState.INSPECT_DEST);

        SyncPipelineState updated = service.skipStage(job, SyncPipelineState.INSPECT_DEST);

        assertTrue(updated.isStageSettled(SyncPipelineState.INSPECT_DEST));
        assertEquals(SyncPipelineState.CONFLICT_CHECK, job.getResumeStageId());
    }

    @Test
    void canOperatorSkipStagesWhenPaused() {
        SyncJob job = SyncJob.builder().status(SyncStatus.PAUSED).build();
        assertTrue(service.canOperatorSkipStages(job));
        assertFalse(service.canOperatorSkipStages(SyncJob.builder().status(SyncStatus.IN_PROGRESS).build()));
    }
}
