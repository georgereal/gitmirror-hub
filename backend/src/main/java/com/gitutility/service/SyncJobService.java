package com.gitutility.service;

import com.gitutility.model.dto.JobUsageResponse;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.LogLevel;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncJobService {

    private static final long DURATION_PERSIST_INTERVAL_MS = 15_000L;

    private final SyncJobRepository syncJobRepository;
    private final SyncAuditLogRepository auditLogRepository;
    private final RepoMappingRepository mappingRepository;
    private final QueueProducerService queueProducerService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final JobCancellationService jobCancellationService;
    private final SyncCheckpointService syncCheckpointService;
    private final JobExecutionStateService jobExecutionStateService;

    private final ConcurrentHashMap<Long, Long> lastDurationPersistMs = new ConcurrentHashMap<>();

    public List<SyncJob> getRecentJobs() {
        return syncJobRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public Page<SyncJob> getAllJobs(Pageable pageable, SyncStatus status, Long mappingId,
                                    TriggerType triggerType, String lane) {
        String normalizedLane = normalizeLane(lane);
        return syncJobRepository.search(status, mappingId, triggerType, normalizedLane, pageable);
    }

    public Page<SyncJob> getAllJobs(Pageable pageable, SyncStatus status, Long mappingId) {
        return getAllJobs(pageable, status, mappingId, null, null);
    }

    private static String normalizeLane(String lane) {
        if (lane == null || lane.isBlank() || "ALL".equalsIgnoreCase(lane)) {
            return null;
        }
        String upper = lane.trim().toUpperCase();
        if (SyncLaneRouter.LANE_FULL.equals(upper) || SyncLaneRouter.LANE_INCREMENTAL.equals(upper)) {
            return upper;
        }
        throw new IllegalArgumentException("lane must be FULL or INCREMENTAL");
    }

    public Optional<SyncJob> getJobById(Long id) {
        return syncJobRepository.findById(id);
    }

    public List<SyncAuditLog> getAuditLogsForJob(Long jobId) {
        return auditLogRepository.findByJobIdOrderByTimestampAsc(jobId);
    }

    public Map<String, Object> getDashboardStats() {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);

        long total24h = syncJobRepository.countJobsSince(since24h);
        long success24h = syncJobRepository.countSuccessJobsSince(since24h);
        long activePairs = mappingRepository.count();
        long queuedCount = syncJobRepository.countByStatus(SyncStatus.QUEUED);
        long inProgressCount = syncJobRepository.countByStatus(SyncStatus.IN_PROGRESS);
        long failedCount = syncJobRepository.countByStatus(SyncStatus.FAILED);
        long dlqCount = syncJobRepository.countByStatus(SyncStatus.DEAD_LETTERED);
        long cancelledCount = syncJobRepository.countByStatus(SyncStatus.CANCELLED);

        double successRate = total24h > 0 ? ((double) success24h / total24h) * 100.0 : 100.0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("activePairs", activePairs);
        stats.put("totalSyncs24h", total24h);
        stats.put("successSyncs24h", success24h);
        stats.put("successRate", Math.round(successRate * 10.0) / 10.0);
        stats.put("queuedCount", queuedCount);
        stats.put("inProgressCount", inProgressCount);
        stats.put("failedCount", failedCount);
        stats.put("deadLetterCount", dlqCount);
        stats.put("cancelledCount", cancelledCount);
        stats.put("interruptedCount", syncJobRepository.countByStatus(SyncStatus.INTERRUPTED));
        stats.put("pausedCount", syncJobRepository.countByStatus(SyncStatus.PAUSED));

        return stats;
    }

    public SyncJob resumeJob(Long jobId) {
        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (!isDispatchable(job.getStatus())) {
            throw new IllegalStateException("Job #" + jobId + " cannot be resumed (status " + job.getStatus() + ")");
        }
        RepoMapping mapping = mappingRepository.findById(job.getMappingId())
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + job.getMappingId()));

        job.setStatus(SyncStatus.QUEUED);
        job.setErrorMessage(null);
        job.setCompletedAt(null);
        job.setDurationMs(null);
        job.setStartedAt(null);
        syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);

        enqueueExistingJob(mapping, job);
        return job;
    }

    public Map<String, Object> dispatchJobs(List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Map.of("dispatched", 0, "skipped", 0, "errors", List.of());
        }
        int dispatched = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (Long jobId : jobIds) {
            try {
                SyncJob job = syncJobRepository.findById(jobId)
                        .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
                if (!isDispatchable(job.getStatus())) {
                    skipped++;
                    continue;
                }
                RepoMapping mapping = mappingRepository.findById(job.getMappingId())
                        .orElseThrow(() -> new IllegalArgumentException("Mapping not found"));
                job.setStatus(SyncStatus.QUEUED);
                job.setErrorMessage(null);
                job.setCompletedAt(null);
                job.setDurationMs(null);
                job.setStartedAt(null);
                syncJobRepository.save(job);
                enqueueExistingJob(mapping, job);
                webSocketNotificationService.notifyJobUpdated(job);
                dispatched++;
            } catch (Exception e) {
                errors.add("Job #" + jobId + ": " + e.getMessage());
            }
        }
        return Map.of("dispatched", dispatched, "skipped", skipped, "errors", errors);
    }

    public static boolean isDispatchable(SyncStatus status) {
        return status == SyncStatus.QUEUED
                || status == SyncStatus.FAILED
                || status == SyncStatus.INTERRUPTED
                || status == SyncStatus.DEAD_LETTERED
                || status == SyncStatus.PAUSED;
    }

    private void enqueueExistingJob(RepoMapping mapping, SyncJob job) {
        queueProducerService.enqueueSyncJob(
                mapping,
                job,
                job.getSourceRepo() != null ? job.getSourceRepo() : mapping.getRepoAUrl(),
                job.getTargetRepo() != null ? job.getTargetRepo() : mapping.getRepoBUrl(),
                job.getRef(),
                job.getBranch(),
                null,
                job.getCommitSha(),
                job.getCommitMessage(),
                job.getAuthor(),
                job.getTriggerType()
        );
    }

    public SyncJob retryJob(Long jobId) {
        SyncJob oldJob = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        RepoMapping mapping = mappingRepository.findById(oldJob.getMappingId())
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + oldJob.getMappingId()));

        SyncJob newJob = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(oldJob.getSourceRepo())
                .targetRepo(oldJob.getTargetRepo())
                .ref(oldJob.getRef())
                .branch(oldJob.getBranch())
                .commitSha(oldJob.getCommitSha())
                .commitMessage("Retry of Job #" + oldJob.getId() + ": " + (oldJob.getCommitMessage() != null ? oldJob.getCommitMessage() : ""))
                .author(oldJob.getAuthor())
                .status(SyncStatus.QUEUED)
                .triggerType(oldJob.getTriggerType())
                .createdAt(Instant.now())
                .build();

        newJob = syncJobRepository.save(newJob);
        webSocketNotificationService.notifyJobUpdated(newJob);

        queueProducerService.enqueueSyncJob(
                mapping,
                newJob,
                newJob.getSourceRepo(),
                newJob.getTargetRepo(),
                newJob.getRef(),
                newJob.getBranch(),
                null,
                newJob.getCommitSha(),
                newJob.getCommitMessage(),
                newJob.getAuthor(),
                newJob.getTriggerType()
        );

        return newJob;
    }

    public SyncJob cancelJob(Long jobId) {
        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (!isCancellable(job.getStatus())) {
            throw new IllegalStateException("Job #" + jobId + " cannot be cancelled (status " + job.getStatus() + ")");
        }
        jobCancellationService.requestCancel(jobId);
        return markCancelled(job, "Cancelled by operator");
    }

    public SyncJob pauseJob(Long jobId) {
        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (!isPausable(job.getStatus())) {
            throw new IllegalStateException("Job #" + jobId + " cannot be paused (status " + job.getStatus() + ")");
        }
        if (job.getStatus() == SyncStatus.QUEUED) {
            job.setStatus(SyncStatus.PAUSED);
            job.setErrorMessage("Paused by operator — dispatch to resume from checkpoint");
            job.setCompletedAt(Instant.now());
            job.setDurationMs(0L);
            job = syncJobRepository.save(job);
            appendPauseAudit(job.getId(), job.getErrorMessage());
            webSocketNotificationService.notifyJobUpdated(job);
            return job;
        }
        jobCancellationService.requestPause(jobId);
        return job;
    }

    public SyncJob skipJobStage(Long jobId, String stageId) {
        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        String skippedStage = (stageId != null && !stageId.isBlank())
                ? stageId.trim()
                : jobExecutionStateService.resolveResumeStageId(jobExecutionStateService.loadPipeline(job));
        SyncPipelineState pipeline = jobExecutionStateService.skipStage(job, stageId);
        String next = job.getResumeStageId();
        String auditMsg = "Operator skipped pipeline stage '" + skippedStage + "'"
                + (next != null ? "; resume cursor → '" + next + "'" : "; all stages settled");
        appendAudit(jobId, LogLevel.WARN, auditMsg);
        syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);
        webSocketNotificationService.notifyPipeline(
                jobId, job.getMappingId(), pipeline.getCurrentStageId(), pipeline.currentLabel(), pipeline.toMap(), null);
        return job;
    }

    public SyncJob markPaused(SyncJob job, String reason) {
        job.setStatus(SyncStatus.PAUSED);
        job.setCompletedAt(Instant.now());
        if (reason != null) {
            job.setErrorMessage(reason);
        }
        if (job.getStartedAt() != null && job.getCompletedAt() != null) {
            job.setDurationMs(Math.max(0, Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis()));
        }
        SyncPipelineState pipeline = jobExecutionStateService.loadPipeline(job);
        jobExecutionStateService.captureInterrupted(job, pipeline, jobExecutionStateService.loadStageProgress(job));
        syncJobRepository.save(job);
        appendPauseAudit(job.getId(), reason != null ? reason : "Paused by operator — checkpoint preserved");
        webSocketNotificationService.notifyJobUpdated(job);
        return job;
    }

    /**
     * Marks queued jobs cancelled. RabbitMQ messages stay until the consumer ACKs them;
     * the worker skips cancelled IDs in milliseconds. Pass {@code mappingId} to cancel one pair only.
     */
    public int cancelQueuedJobs(Long mappingId) {
        List<SyncJob> jobs = mappingId != null
                ? syncJobRepository.findByStatusAndMappingId(SyncStatus.QUEUED, mappingId)
                : syncJobRepository.findByStatus(SyncStatus.QUEUED);
        Instant now = Instant.now();
        int count = 0;
        for (SyncJob job : jobs) {
            jobCancellationService.requestCancel(job.getId());
            if (job.getMappingId() != null) {
                syncCheckpointService.resetPairProgress(job.getMappingId());
            }
            job.setStatus(SyncStatus.CANCELLED);
            job.setCompletedAt(now);
            job.setDurationMs(0L);
            job.setErrorMessage("Cancelled by operator");
            syncJobRepository.save(job);
            appendCancelAudit(job.getId(), "Cancelled by operator; RabbitMQ message will be ACK'd on pickup");
            webSocketNotificationService.notifyJobUpdated(job);
            count++;
        }
        log.info("Cancelled {} queued job(s){}", count, mappingId != null ? " for mapping " + mappingId : "");
        return count;
    }

    public SyncJob markCancelled(SyncJob job, String reason) {
        job.setStatus(SyncStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        if (reason != null) {
            job.setErrorMessage(reason);
        }
        if (job.getStartedAt() != null && job.getCompletedAt() != null) {
            job.setDurationMs(Math.max(0, Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis()));
        }
        if (job.getMappingId() != null) {
            syncCheckpointService.clearResumeCheckpoint(job.getMappingId());
        }
        job = syncJobRepository.save(job);
        appendCancelAudit(job.getId(), reason != null
                ? reason + " — resume checkpoint cleared; next Smart sync still tip-checks (use Start fresh only to force re-fetch)"
                : "Cancelled by operator — resume checkpoint cleared; next Smart sync still tip-checks (use Start fresh only to force re-fetch)");
        webSocketNotificationService.notifyJobUpdated(job);
        return job;
    }

    private void appendCancelAudit(Long jobId, String message) {
        appendAudit(jobId, com.gitutility.model.enums.LogLevel.WARN, message);
    }

    private void appendAudit(Long jobId, LogLevel level, String message) {
        if (jobId == null || auditLogRepository == null) {
            return;
        }
        auditLogRepository.save(SyncAuditLog.builder()
                .jobId(jobId)
                .level(level)
                .message(message)
                .timestamp(Instant.now())
                .build());
    }

    private void appendPauseAudit(Long jobId, String message) {
        appendAudit(jobId, LogLevel.INFO, message);
    }

    public static boolean isCancellable(SyncStatus status) {
        return status == SyncStatus.QUEUED || status == SyncStatus.IN_PROGRESS;
    }

    public static boolean isPausable(SyncStatus status) {
        return status == SyncStatus.QUEUED || status == SyncStatus.IN_PROGRESS;
    }

    /**
     * Persists running wall-clock duration for IN_PROGRESS jobs (throttled ~15s) so UI refresh
     * and historical views stay accurate during long mirrors.
     */
    public void touchRunningDuration(Long jobId) {
        if (jobId == null) {
            return;
        }
        syncJobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != SyncStatus.IN_PROGRESS || job.getStartedAt() == null) {
                return;
            }
            long elapsed = Math.max(0, Duration.between(job.getStartedAt(), Instant.now()).toMillis());
            long now = System.currentTimeMillis();
            Long last = lastDurationPersistMs.get(jobId);
            if (last != null && now - last < DURATION_PERSIST_INTERVAL_MS
                    && job.getDurationMs() != null && job.getDurationMs() == elapsed) {
                return;
            }
            lastDurationPersistMs.put(jobId, now);
            job.setDurationMs(elapsed);
            syncJobRepository.save(job);
        });
    }

    public void clearDurationPersist(Long jobId) {
        if (jobId != null) {
            lastDurationPersistMs.remove(jobId);
        }
    }

    public JobUsageResponse getJobUsage(Instant since, int limit) {
        Instant windowStart = since != null ? since : Instant.now().minus(6, ChronoUnit.HOURS);
        int cap = Math.min(Math.max(limit, 1), 100);
        List<SyncJob> found = syncJobRepository.findForUsageWindow(windowStart);
        List<JobUsageResponse.JobUsageRow> rows = found.stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(usageScore(b), usageScore(a));
                    if (cmp != 0) {
                        return cmp;
                    }
                    Instant as = a.getStartedAt() != null ? a.getStartedAt() : Instant.EPOCH;
                    Instant bs = b.getStartedAt() != null ? b.getStartedAt() : Instant.EPOCH;
                    return bs.compareTo(as);
                })
                .limit(cap)
                .map(SyncJobService::toUsageRow)
                .toList();
        return JobUsageResponse.builder()
                .since(windowStart)
                .capturedAt(Instant.now())
                .jobs(rows)
                .build();
    }

    static int usageScore(SyncJob job) {
        if (job == null) {
            return 0;
        }
        return nz(job.getRestCallCount())
                + nz(job.getGraphqlCallCount())
                + nz(job.getLfsApiCallCount())
                + nz(job.getLfsTransferHttpCount())
                + nz(job.getGitHttpFetchCount())
                + nz(job.getGitHttpPushBatchCount());
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    private static JobUsageResponse.JobUsageRow toUsageRow(SyncJob job) {
        Long duration = job.getDurationMs();
        if ((duration == null || duration <= 0) && job.getStartedAt() != null && job.getCompletedAt() != null) {
            duration = Math.max(0L, Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        return JobUsageResponse.JobUsageRow.builder()
                .id(job.getId())
                .mappingId(job.getMappingId())
                .pairName(job.getPairName())
                .status(job.getStatus() != null ? job.getStatus().name() : null)
                .triggerType(job.getTriggerType() != null ? job.getTriggerType().name() : null)
                .branch(job.getBranch())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .durationMs(duration)
                .restCallCount(nz(job.getRestCallCount()))
                .graphqlCallCount(nz(job.getGraphqlCallCount()))
                .graphqlPointsUsed(nz(job.getGraphqlPointsUsed()))
                .graphql429Count(nz(job.getGraphql429Count()))
                .lfsApiCallCount(nz(job.getLfsApiCallCount()))
                .lfsTransferHttpCount(nz(job.getLfsTransferHttpCount()))
                .gitHttpFetchCount(nz(job.getGitHttpFetchCount()))
                .gitHttpPushBatchCount(nz(job.getGitHttpPushBatchCount()))
                .rateLimit429Count(nz(job.getRateLimit429Count()))
                .gitHttpThrottleCount(nz(job.getGitHttpThrottleCount()))
                .gitReadBytes(job.getGitReadBytes())
                .gitWriteBytes(job.getGitWriteBytes())
                .lfsBytes(job.getLfsBytes())
                .bytesTransferred(job.getBytesTransferred())
                .provider(job.getProviderTrafficProvider())
                .usageScore(usageScore(job))
                .build();
    }
}
