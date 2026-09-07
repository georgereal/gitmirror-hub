package com.gitutility.service;

import com.gitutility.model.dto.JobStageProgress;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * Persists per-job pipeline cursor and stage-local progress. Resume decisions use this state —
 * not live git mirror inspection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobExecutionStateService {

    private static final Set<SyncStatus> RESUMABLE_STATUSES = EnumSet.of(
            SyncStatus.PAUSED,
            SyncStatus.INTERRUPTED,
            SyncStatus.FAILED,
            SyncStatus.DEAD_LETTERED
    );

    private final SyncJobRepository syncJobRepository;

    public boolean isResumable(SyncJob job) {
        if (job == null) {
            return false;
        }
        if (job.getResumeStageId() != null && !job.getResumeStageId().isBlank()) {
            return true;
        }
        if (job.getPipelineJson() == null || job.getPipelineJson().isBlank()) {
            return false;
        }
        SyncPipelineState pipeline = SyncPipelineState.fromJson(job.getPipelineJson());
        return pipeline.firstPendingStageId() != null;
    }

    public boolean shouldResume(SyncJob job) {
        return job != null && (RESUMABLE_STATUSES.contains(job.getStatus()) || isResumable(job));
    }

    public SyncPipelineState loadPipeline(SyncJob job) {
        if (job != null && job.getPipelineJson() != null && !job.getPipelineJson().isBlank()) {
            return SyncPipelineState.fromJson(job.getPipelineJson());
        }
        return SyncPipelineState.initial();
    }

    public String resolveResumeStageId(SyncPipelineState pipeline) {
        if (pipeline == null) {
            return SyncPipelineState.FAST_PATH;
        }
        if (pipeline.getCurrentStageId() != null && !pipeline.getCurrentStageId().isBlank()) {
            return pipeline.getCurrentStageId();
        }
        String pending = pipeline.firstPendingStageId();
        return pending != null ? pending : SyncPipelineState.LFS;
    }

    public JobStageProgress loadStageProgress(SyncJob job) {
        if (job == null || job.getStageProgressJson() == null) {
            return JobStageProgress.empty();
        }
        return JobStageProgress.fromJson(job.getStageProgressJson());
    }

    public JobStageProgress loadStageProgressByJobId(Long jobId) {
        if (jobId == null) {
            return JobStageProgress.empty();
        }
        return syncJobRepository.findById(jobId)
                .map(this::loadStageProgress)
                .orElse(JobStageProgress.empty());
    }

    /**
     * Returns true when this stage still needs to run for the current job resume cursor.
     */
    public boolean shouldExecuteStage(SyncPipelineState pipeline, String resumeStageId, String stageId) {
        if (pipeline == null || stageId == null) {
            return true;
        }
        if (pipeline.isStageSettled(stageId)) {
            return false;
        }
        if (resumeStageId == null || resumeStageId.isBlank()) {
            return true;
        }
        return SyncPipelineState.stageIndex(stageId) >= SyncPipelineState.stageIndex(resumeStageId);
    }

    public boolean isMetadataPhase(String resumeStageId) {
        return SyncPipelineState.isAtOrAfter(resumeStageId, SyncPipelineState.PR_METADATA);
    }

    public void persistProgress(Long jobId, SyncPipelineState pipeline, JobStageProgress stageProgress) {
        if (jobId == null) {
            return;
        }
        try {
            syncJobRepository.findById(jobId).ifPresent(job -> {
                if (pipeline != null) {
                    job.setPipelineJson(pipeline.toJson());
                    job.setResumeStageId(resolveResumeStageId(pipeline));
                }
                if (stageProgress != null) {
                    job.setStageProgressJson(stageProgress.toJson());
                }
                syncJobRepository.save(job);
            });
        } catch (Exception e) {
            log.debug("Could not persist job execution state for #{}: {}", jobId, e.getMessage());
        }
    }

    public void captureInterrupted(SyncJob job, SyncPipelineState pipeline, JobStageProgress stageProgress) {
        if (job == null) {
            return;
        }
        if (pipeline != null) {
            job.setPipelineJson(pipeline.toJson());
            job.setResumeStageId(resolveResumeStageId(pipeline));
        }
        if (stageProgress != null) {
            job.setStageProgressJson(stageProgress.toJson());
        }
    }

    public void clearProgress(Long jobId) {
        if (jobId == null) {
            return;
        }
        try {
            syncJobRepository.findById(jobId).ifPresent(job -> {
                job.setResumeStageId(null);
                job.setStageProgressJson(null);
                syncJobRepository.save(job);
            });
        } catch (Exception e) {
            log.debug("Could not clear job execution state for #{}: {}", jobId, e.getMessage());
        }
    }

    public boolean canOperatorSkipStages(SyncJob job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        return switch (job.getStatus()) {
            case PAUSED, INTERRUPTED, FAILED, DEAD_LETTERED, QUEUED -> true;
            default -> false;
        };
    }

    /**
     * Marks a pipeline stage skipped and advances the job-local resume cursor.
     */
    public SyncPipelineState skipStage(SyncJob job, String stageId) {
        if (job == null) {
            throw new IllegalArgumentException("Job is required");
        }
        if (!canOperatorSkipStages(job)) {
            throw new IllegalStateException("Job #" + job.getId()
                    + " cannot skip stages while " + job.getStatus()
                    + " — pause or interrupt the job first");
        }
        SyncPipelineState pipeline = loadPipeline(job);
        String targetStage = (stageId != null && !stageId.isBlank())
                ? stageId.trim()
                : resolveResumeStageId(pipeline);
        if (targetStage == null || targetStage.isBlank()) {
            throw new IllegalStateException("No stage to skip");
        }
        if (pipeline.isStageSettled(targetStage)) {
            throw new IllegalStateException("Stage '" + targetStage + "' is already complete or skipped");
        }
        pipeline.markSkipped(targetStage, "Skipped by operator");
        String nextStage = SyncPipelineState.nextStageAfter(targetStage);
        while (nextStage != null && pipeline.isStageSettled(nextStage)) {
            nextStage = SyncPipelineState.nextStageAfter(nextStage);
        }
        job.setPipelineJson(pipeline.toJson());
        job.setResumeStageId(nextStage);
        return pipeline;
    }
}
