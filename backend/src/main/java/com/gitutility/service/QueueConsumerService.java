package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.LogLevel;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueueConsumerService {

    private final GitSyncEngine gitSyncEngine;
    private final SyncJobRepository syncJobRepository;
    private final RepoMappingRepository mappingRepository;
    private final SimulationService simulationService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final PullRequestSyncService pullRequestSyncService;
    private final ReleaseAndStatusSyncService releaseAndStatusSyncService;
    private final CircuitBreakerManagerService circuitBreakerManager;
    private final ProviderRateMeter providerRateMeter;
    private final JobCancellationService jobCancellationService;
    private final SyncJobService syncJobService;
    private final ConsumerRuntimeRegistry consumerRuntimeRegistry;
    private final SyncCheckpointService syncCheckpointService;
    private final HubMetrics hubMetrics;
    private final JobExecutionStateService jobExecutionStateService;
    private final PairMirrorSnapshotService pairMirrorSnapshotService;
    private final PairDiffSnapshotService pairDiffSnapshotService;
    private final PairLeaseService pairLeaseService;
    private final InstanceIdentity instanceIdentity;
    @Lazy
    private final QueueProducerService queueProducerService;
    private final ScmInstallationKeyResolver installationKeyResolver;


    @Value("${git-utility.throttle.max-concurrent-git-pushes:5}")
    private volatile int maxConcurrentPushes;

    @Value("${git-utility.throttle.metadata-sync-interval-seconds:30}")
    private volatile int metadataSyncIntervalSeconds;

    private final Map<Long, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastPrSyncTimes = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastReleaseSyncTimes = new ConcurrentHashMap<>();

    private volatile Semaphore pushConcurrencyLimiter;

    @PostConstruct
    public void init() {
        pushConcurrencyLimiter = new Semaphore(Math.max(1, maxConcurrentPushes));
        log.info("QueueConsumerService initialized. Max Concurrent Pushes: {}, Metadata Throttle: {}s",
                maxConcurrentPushes, metadataSyncIntervalSeconds);
    }

    public synchronized void updateOperationalParameters(int maxPushes, int metadataIntervalSec) {
        if (maxPushes > 0 && maxPushes != this.maxConcurrentPushes) {
            this.maxConcurrentPushes = maxPushes;
            this.pushConcurrencyLimiter = new Semaphore(maxPushes);
        }
        if (metadataIntervalSec >= 0) {
            this.metadataSyncIntervalSeconds = metadataIntervalSec;
        }
        log.info("QueueConsumerService updated operational parameters: maxConcurrentPushes={}, metadataInterval={}s",
                this.maxConcurrentPushes, this.metadataSyncIntervalSeconds);
    }

    @RabbitListener(
            id = SyncLaneRouter.FULL_CONSUMER_ID,
            queues = "${git-utility.queue.main-queue:git.sync.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeFullMirrorEvent(SyncEventMessage event) throws Exception {
        consumeSyncEvent(event, SyncLaneRouter.LANE_FULL, SyncLaneRouter.FULL_CONSUMER_ID);
    }

    @RabbitListener(
            id = SyncLaneRouter.INCREMENTAL_CONSUMER_ID,
            queues = "${git-utility.queue.incremental-queue:git.sync.incremental.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void consumeIncrementalEvent(SyncEventMessage event) throws Exception {
        consumeSyncEvent(event, SyncLaneRouter.LANE_INCREMENTAL, SyncLaneRouter.INCREMENTAL_CONSUMER_ID);
    }

    /**
     * Shared execution path for both lanes. Returning without throw ACKs the AMQP message,
     * including cancelled jobs skipped on pickup.
     */
    public void consumeSyncEvent(SyncEventMessage event) throws Exception {
        consumeSyncEvent(event, SyncLaneRouter.lane(event.getRef(), event.getBranch()),
                SyncLaneRouter.isFullMirror(event)
                        ? SyncLaneRouter.FULL_CONSUMER_ID
                        : SyncLaneRouter.INCREMENTAL_CONSUMER_ID);
    }

    public void consumeSyncEvent(SyncEventMessage event, String lane, String listenerId) throws Exception {
        if (consumerRuntimeRegistry != null) {
            consumerRuntimeRegistry.bind(lane, listenerId, event.getJobId(), event.getPairName(), event.getRef());
        }
        try {
            processSyncEvent(event, lane);
        } finally {
            if (consumerRuntimeRegistry != null) {
                consumerRuntimeRegistry.unbind();
            }
        }
    }

    private void processSyncEvent(SyncEventMessage event) throws Exception {
        processSyncEvent(event, SyncLaneRouter.lane(event.getRef(), event.getBranch()));
    }

    private void processSyncEvent(SyncEventMessage event, String lane) throws Exception {
        log.info("Received SyncEvent from queue for Job #{} [{}] on ref: {}",
                event.getJobId(), event.getPairName(), event.getRef());

        SyncJob job = syncJobRepository.findById(event.getJobId()).orElse(null);
        if (job == null) {
            log.warn("Job not found in database for ID: {}", event.getJobId());
            if (consumerRuntimeRegistry != null) {
                consumerRuntimeRegistry.markSkipping();
            }
            return;
        }

        if (job.getStatus() == SyncStatus.CANCELLED
                || (jobCancellationService != null && jobCancellationService.isCancelRequested(job.getId()))) {
            log.info("Skipping cancelled Job #{} [{}]", job.getId(), event.getPairName());
            if (consumerRuntimeRegistry != null) {
                consumerRuntimeRegistry.markSkipping();
            }
            if (job.getStatus() != SyncStatus.CANCELLED && syncJobService != null) {
                syncJobService.markCancelled(job, "Cancelled by operator");
            }
            return;
        }

        if (job.getStatus() == SyncStatus.PAUSED) {
            log.info("Skipping paused Job #{} [{}] — dispatch to resume", job.getId(), event.getPairName());
            if (consumerRuntimeRegistry != null) {
                consumerRuntimeRegistry.markSkipping();
            }
            return;
        }

        job.setStatus(SyncStatus.IN_PROGRESS);
        job.setStartedAt(Instant.now());
        job.setDurationMs(null);
        job.setCompletedAt(null);
        job.setAttemptCount(job.getAttemptCount() + 1);
        job.setCancelRequested(false);
        job.setPauseRequested(false);
        if (instanceIdentity != null) {
            job.setWorkerInstanceId(instanceIdentity.getInstanceId());
        }
        syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);

        if (jobCancellationService != null) {
            jobCancellationService.registerRunning(job.getId());
        }

        if (pairLeaseService != null) {
            try {
                pairLeaseService.acquire(event.getMappingId(), job.getId());
            } catch (PairLeaseBusyException busy) {
                log.info("Job #{} waiting for pair lease on mapping {}: {}",
                        job.getId(), event.getMappingId(), busy.getMessage());
                if (jobCancellationService != null) {
                    jobCancellationService.unregisterRunning(job.getId());
                }
                job.setStatus(SyncStatus.QUEUED);
                job.setWorkerInstanceId(null);
                job.setStartedAt(null);
                job.setAttemptCount(Math.max(0, job.getAttemptCount() - 1));
                syncJobRepository.save(job);
                webSocketNotificationService.notifyJobUpdated(job);
                if (queueProducerService != null) {
                    queueProducerService.republishSyncEvent(event);
                }
                return;
            }
        }

        ReentrantLock lock = repoLocks.computeIfAbsent(event.getMappingId(), k -> new ReentrantLock());
        lock.lock();

        boolean acquiredPermit = false;
        boolean leaseHeld = pairLeaseService != null;
        GitSyncEngine.SyncResult result = null;
        try {
            if (pairLeaseService != null) {
                pairLeaseService.renew(event.getMappingId(), job.getId());
            }
            // 1. Check for injected faults (e.g., simulated network outage / pause / target down)
            simulationService.checkInjectedFaults();

            // 2. Acquire Concurrency Rate-Limiting Permit (Shield against Git provider 429s)
            if (pushConcurrencyLimiter != null) {
                pushConcurrencyLimiter.acquire();
                acquiredPermit = true;
            }

            // 3. Run JGit Mirror Synchronization (Includes Fast-Path reachability check)
            if (providerRateMeter != null) {
                providerRateMeter.bindJob(job.getId());
            }
            bindQuotaContext(event);
            try {
                result = gitSyncEngine.executeSync(event);
            } finally {
                ScmQuotaContext.clear();
            }
            captureGitMirrorMetrics(job, result);
            if (pairMirrorSnapshotService != null && result.success) {
                pairMirrorSnapshotService.recordGitMirrorProgress(event.getMappingId(), job.getId(), result);
                webSocketNotificationService.notifyJobUpdated(job);
            }

            if (jobCancellationService != null && jobCancellationService.isPauseRequested(job.getId())) {
                throw new JobPausedException(job.getId());
            }

            if (jobCancellationService != null && jobCancellationService.isCancelRequested(job.getId())) {
                throw new JobCancelledException(job.getId());
            }

            int prsSynced = 0;
            SyncPipelineState pipeline = result.pipeline;
            boolean pairMetadata = SyncLaneRouter.includePairMetadata(event);
            // Pair-wide PR/release REST sync only on full-mirror jobs (and dedicated UI actions).
            if (!result.fastPathShortCircuited && pairMetadata && shouldSyncMetadata(event.getMappingId(), lastPrSyncTimes)
                    && (pipeline == null || !pipeline.isStageSettled(SyncPipelineState.PR_METADATA))) {
                if (pipeline != null) {
                    pipeline.markCurrent(SyncPipelineState.PR_METADATA);
                    broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                }
                gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO, "Synchronizing open pull request metadata...");
                try {
                    prsSynced = pullRequestSyncService.syncOpenPullRequests(
                            event.getMappingId(), event.getSourceRepoUrl(), event.getTargetRepoUrl(),
                            job.getId(),
                            msg -> {
                                gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO, msg);
                                if (pipeline != null) {
                                    String detail = msg.startsWith("PR metadata · ")
                                            ? msg.substring("PR metadata · ".length())
                                            : msg;
                                    if (detail.length() > 96) {
                                        detail = detail.substring(0, 93) + "...";
                                    }
                                    pipeline.markCurrent(SyncPipelineState.PR_METADATA, detail);
                                    broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                                }
                            });
                    lastPrSyncTimes.put(event.getMappingId(), Instant.now());
                    if (pipeline != null) {
                        pipeline.markDone(SyncPipelineState.PR_METADATA, prsSynced + " PR(s)");
                        broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                    }
                    gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO,
                            "Pull request metadata sync finished: " + prsSynced + " PR(s).");
                    if (prsSynced > 0) {
                        log.info("Synchronized {} open pull request(s) for pair '{}'", prsSynced, event.getPairName());
                    }
                    job.setPrsSyncedCount(prsSynced);
                    syncJobRepository.save(job);
                    webSocketNotificationService.notifyJobUpdated(job);
                } catch (Exception prEx) {
                    if (pipeline != null) {
                        pipeline.markDone(SyncPipelineState.PR_METADATA, "Completed with notice");
                        broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                    }
                    gitSyncEngine.logJobAudit(job.getId(), LogLevel.WARN,
                            "Pull request metadata sync completed with notice: " + prEx.getMessage());
                    log.debug("Pull request metadata sync notice: {}", prEx.getMessage());
                }
            } else if (pipeline != null && !result.fastPathShortCircuited) {
                pipeline.markSkipped(SyncPipelineState.PR_METADATA,
                        pairMetadata ? "Throttled" : "Branch-only job");
                broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
            }

            int releasesSynced = 0;
            if (!result.fastPathShortCircuited && pairMetadata && shouldSyncMetadata(event.getMappingId(), lastReleaseSyncTimes)
                    && (pipeline == null || !pipeline.isStageSettled(SyncPipelineState.RELEASES))) {
                if (pipeline != null) {
                    pipeline.markCurrent(SyncPipelineState.RELEASES);
                    broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                }
                gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO, "Synchronizing releases and CI metadata...");
                try {
                    releasesSynced = releaseAndStatusSyncService.syncReleases(
                            event.getMappingId(), event.getSourceRepoUrl(), event.getTargetRepoUrl(),
                            msg -> {
                                gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO, msg);
                                if (pipeline != null) {
                                    String detail = msg.startsWith("Releases · ")
                                            ? msg.substring("Releases · ".length()) : msg;
                                    if (detail.length() > 96) {
                                        detail = detail.substring(0, 93) + "...";
                                    }
                                    pipeline.markCurrent(SyncPipelineState.RELEASES, detail);
                                    broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                                }
                            });
                    lastReleaseSyncTimes.put(event.getMappingId(), Instant.now());
                    if (pipeline != null) {
                        pipeline.markDone(SyncPipelineState.RELEASES, releasesSynced + " release(s)");
                        broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                    }
                    gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO,
                            "Release metadata sync finished: " + releasesSynced + " release(s).");
                    if (releasesSynced > 0) {
                        log.info("Synchronized {} release(s) with binary assets for pair '{}'", releasesSynced, event.getPairName());
                    }
                    pairDiffSnapshotService.updateReleases(event.getMappingId(), releasesSynced, releasesSynced);
                } catch (Exception relEx) {
                    if (pipeline != null) {
                        pipeline.markDone(SyncPipelineState.RELEASES, "Completed with notice");
                        broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                    }
                    gitSyncEngine.logJobAudit(job.getId(), LogLevel.WARN,
                            "Release metadata sync completed with notice: " + relEx.getMessage());
                    log.debug("Release metadata sync notice: {}", relEx.getMessage());
                }
            } else if (pipeline != null && !result.fastPathShortCircuited) {
                pipeline.markSkipped(SyncPipelineState.RELEASES,
                        pairMetadata ? "Throttled" : "Branch-only job");
                broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
            }

            boolean resumingJob = !event.isForceSourceFetch() && jobExecutionStateService.shouldResume(job);
            String resumeStageId = jobExecutionStateService.resolveResumeStageId(
                    jobExecutionStateService.loadPipeline(job));
            if (!result.fastPathShortCircuited && pairMetadata
                    && (pipeline == null || !pipeline.isStageSettled(SyncPipelineState.LFS))) {
                    gitSyncEngine.executeLfsSync(event, job.getId(), pipeline, result, resumingJob, resumeStageId);
                if (pipeline != null) {
                    broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
                }
                if (pairDiffSnapshotService != null && result.lfsObjectsCount > 0) {
                    pairDiffSnapshotService.updateLfs(event.getMappingId(), result.lfsObjectsCount, result.lfsSyncedCount);
                }
                job.setLfsObjectsCount(result.lfsObjectsCount);
                syncJobRepository.save(job);
                webSocketNotificationService.notifyJobUpdated(job);
            } else if (pipeline != null && !result.fastPathShortCircuited) {
                pipeline.markSkipped(SyncPipelineState.LFS,
                        pairMetadata ? "Already complete" : "Branch-only job");
                broadcastPipeline(job.getId(), event.getMappingId(), pipeline);
            }

            if (pairMirrorSnapshotService != null && result.success) {
                pairMirrorSnapshotService.recordGitMirrorProgress(event.getMappingId(), job.getId(), result);
            }

            // 6. Record metadata metrics on job
            job.setBranchesCount(result.branchesCount);
            job.setTagsCount(result.tagsCount);
            job.setLfsObjectsCount(result.lfsObjectsCount);
            job.setPrsSyncedCount(prsSynced);
            job.setReleasesCount(releasesSynced);
            job.setBytesTransferred(result.bytesTransferred);
            job.setObjectsReceived(result.objectsReceived);
            job.setLfsBytes(result.lfsBytes);
            job.setSourceAccessMode(result.sourceAccessMode);
            job.setRejectedPushRefs(result.rejectedPushRefs);
            if (pipeline != null) {
                job.setPipelineJson(pipeline.toJson());
            } else if (result.pipelineJson != null) {
                job.setPipelineJson(result.pipelineJson);
            }
            if (providerRateMeter != null) {
                providerRateMeter.copyTo(job);
            }

            Instant completedAt = Instant.now();
            long wallClockMs = job.getStartedAt() != null
                    ? Math.max(0, Duration.between(job.getStartedAt(), completedAt).toMillis())
                    : result.durationMs;

            String formattedSummary = String.format("%s · %s object%s · %d LFS · %d branch%s, %d tag%s, %d PR%s in %s%s",
                    GitSyncEngine.formatBytes(result.bytesTransferred),
                    formatCount(result.objectsReceived), result.objectsReceived == 1 ? "" : "s",
                    result.lfsObjectsCount,
                    result.branchesCount, result.branchesCount == 1 ? "" : "es",
                    result.tagsCount, result.tagsCount == 1 ? "" : "s",
                    prsSynced, prsSynced == 1 ? "" : "s",
                    formatDuration(wallClockMs),
                    "PUBLIC".equals(result.sourceAccessMode) ? " (public source)" : "");

            if (jobCancellationService != null && jobCancellationService.isPauseRequested(job.getId())) {
                throw new JobPausedException(job.getId());
            }

            if (jobCancellationService != null && jobCancellationService.isCancelRequested(job.getId())) {
                throw new JobCancelledException(job.getId());
            }

            // 7. Mark Status
            if (result.conflictIsolated) {
                job.setStatus(SyncStatus.CONFLICT_ISOLATED);
                job.setErrorMessage("Divergence isolated non-destructively on: " + result.isolatedBranch);
                job.setSummaryMessage(result.message);
            } else {
                job.setStatus(SyncStatus.SUCCESS);
                job.setErrorMessage(null);
                job.setSummaryMessage(formattedSummary);
            }
            job.setCompletedAt(completedAt);
            job.setDurationMs(wallClockMs);
            syncJobRepository.save(job);
            recordJobOutcome(lane, job);

            // Record success on circuit breaker manager
            circuitBreakerManager.recordSuccess();

            // Update mapping summary
            mappingRepository.findById(event.getMappingId()).ifPresent(mapping -> {
                mapping.setLastSyncAt(Instant.now());
                mapping.setLastSyncStatus(job.getStatus());
                mappingRepository.save(mapping);
            });

            gitSyncEngine.logJobAudit(job.getId(), LogLevel.INFO,
                    "Job finished (" + job.getStatus() + "): " + formattedSummary);
            log.info("Job #{} completed with status {} in {}ms", job.getId(), job.getStatus(), job.getDurationMs());

            if (job.getStatus() == SyncStatus.SUCCESS) {
                jobExecutionStateService.clearProgress(job.getId());
                syncCheckpointService.clearCheckpoint(event.getMappingId());
            }

        } catch (JobPausedException e) {
            Thread.interrupted();
            captureGitMirrorMetrics(job, result);
            log.info("Job #{} paused during execution", job.getId());
            if (syncJobService != null) {
                syncJobService.markPaused(job, "Paused by operator — checkpoint preserved");
            } else {
                job.setStatus(SyncStatus.PAUSED);
                job.setCompletedAt(Instant.now());
                job.setErrorMessage(e.getMessage());
                syncJobRepository.save(job);
            }
            recordJobOutcome(lane, job);
        } catch (JobCancelledException e) {
            Thread.interrupted();
            captureGitMirrorMetrics(job, result);
            log.info("Job #{} cancelled during execution", job.getId());
            if (syncJobService != null) {
                syncJobService.markCancelled(job, e.getMessage());
            } else {
                job.setStatus(SyncStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                job.setErrorMessage(e.getMessage());
                syncJobRepository.save(job);
            }
            recordJobOutcome(lane, job);
        } catch (Exception e) {
            if (jobCancellationService != null && jobCancellationService.isPauseRequested(job.getId())) {
                Thread.interrupted();
                log.info("Job #{} aborted after pause request: {}", job.getId(), e.getMessage());
                if (syncJobService != null) {
                    syncJobService.markPaused(job, "Paused by operator — checkpoint preserved");
                }
                recordJobOutcome(lane, job);
                return;
            }
            if (jobCancellationService != null && jobCancellationService.isCancelRequested(job.getId())) {
                Thread.interrupted();
                log.info("Job #{} aborted after cancel request: {}", job.getId(), e.getMessage());
                if (syncJobService != null) {
                    syncJobService.markCancelled(job, "Cancelled by operator");
                }
                recordJobOutcome(lane, job);
                return;
            }
            String cleanError = GitSyncEngine.cleanRootCauseMessage(e);
            log.error("Failed to execute sync for Job #{}: {}", job.getId(), cleanError);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(cleanError);
            if (job.getStartedAt() != null) {
                job.setDurationMs(Math.max(0, Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis()));
            }
            if (providerRateMeter != null) {
                providerRateMeter.copyTo(job);
            }

            boolean isPermanentFailure = false;
            if (job.getAttemptCount() >= job.getMaxAttempts()) {
                job.setStatus(SyncStatus.DEAD_LETTERED);
                isPermanentFailure = true;
                log.warn("Job #{} exhausted {} attempts. Moving to DEAD_LETTERED status.",
                        job.getId(), job.getMaxAttempts());
            } else {
                job.setStatus(SyncStatus.FAILED);
            }

            syncJobRepository.save(job);
            recordJobOutcome(lane, job);

            mappingRepository.findById(event.getMappingId()).ifPresent(mapping -> {
                mapping.setLastSyncAt(Instant.now());
                mapping.setLastSyncStatus(job.getStatus());
                mappingRepository.save(mapping);
            });

            // If job permanently failed after exhausting all retries, record on circuit breaker
            if (isPermanentFailure) {
                circuitBreakerManager.recordPermanentFailure("Job #" + job.getId() + " dead-lettered: " + cleanError);
            }

            // Re-throw so Spring AMQP / RabbitMQ can route to retry advice or DLX
            throw e;
        } finally {
            if (providerRateMeter != null) {
                providerRateMeter.unbindJob();
            }
            if (syncJobService != null) {
                syncJobService.clearDurationPersist(job.getId());
            }
            if (acquiredPermit && pushConcurrencyLimiter != null) {
                pushConcurrencyLimiter.release();
            }
            lock.unlock();
            if (leaseHeld && pairLeaseService != null) {
                try {
                    pairLeaseService.release(event.getMappingId());
                } catch (Exception e) {
                    log.warn("Failed to release pair lease for mapping {}: {}", event.getMappingId(), e.getMessage());
                }
            }
            if (jobCancellationService != null) {
                jobCancellationService.unregisterRunning(job.getId());
            }
            Thread.interrupted();
            webSocketNotificationService.notifyJobUpdated(job);
        }
    }

    private void recordJobOutcome(String lane, SyncJob job) {
        if (hubMetrics == null || job == null || job.getStatus() == null) {
            return;
        }
        long durationMs = job.getDurationMs() != null ? job.getDurationMs() : 0L;
        if (durationMs <= 0 && job.getStartedAt() != null && job.getCompletedAt() != null) {
            durationMs = Math.max(0L, Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        hubMetrics.recordJobOutcome(lane, job.getStatus().name(), durationMs);
    }

    private void captureGitMirrorMetrics(SyncJob job, GitSyncEngine.SyncResult result) {
        if (job == null || result == null || !result.success) {
            return;
        }
        job.setBranchesCount(result.branchesCount);
        job.setTagsCount(result.tagsCount);
        job.setLfsObjectsCount(result.lfsObjectsCount);
        job.setBytesTransferred(result.bytesTransferred);
        job.setObjectsReceived(result.objectsReceived);
        job.setLfsBytes(result.lfsBytes);
        job.setSourceAccessMode(result.sourceAccessMode);
        job.setRejectedPushRefs(result.rejectedPushRefs);
        if (result.pipeline != null) {
            job.setPipelineJson(result.pipeline.toJson());
        } else if (result.pipelineJson != null) {
            job.setPipelineJson(result.pipelineJson);
        }
        if (providerRateMeter != null) {
            providerRateMeter.copyTo(job);
        }
        syncJobRepository.save(job);
    }

    private void bindQuotaContext(SyncEventMessage event) {
        if (event == null) {
            return;
        }
        String url = event.getTargetRepoUrl() != null ? event.getTargetRepoUrl() : event.getSourceRepoUrl();
        String provider = "github";
        if (url != null) {
            String lower = url.toLowerCase();
            if (lower.contains("gitlab")) {
                provider = "gitlab";
            } else if (lower.contains("bitbucket")) {
                provider = "bitbucket";
            } else if (lower.contains("github") && !lower.contains("github.com")) {
                provider = "ghes";
            } else if (!lower.contains("github.com") && lower.contains("http")) {
                // self-hosted github enterprise style hosts
                if (!lower.contains("gitlab") && !lower.contains("bitbucket")) {
                    provider = "ghes";
                }
            }
        }
        String install = installationKeyResolver != null
                ? installationKeyResolver.resolve(provider)
                : "default";
        String repo = null;
        try {
            if (url != null) {
                // https://github.com/owner/repo(.git)
                String path = java.net.URI.create(url.replace(".git", "")).getPath();
                if (path != null) {
                    String[] parts = path.replaceAll("^/+", "").split("/");
                    if (parts.length >= 2) {
                        repo = parts[0] + "/" + parts[1];
                    }
                }
            }
        } catch (Exception ignored) {
        }
        ScmQuotaContext.bind(provider, install, repo);
    }

    private boolean shouldSyncMetadata(Long mappingId, Map<Long, Instant> lastSyncMap) {
        if (mappingId == null) return false;
        Instant last = lastSyncMap.get(mappingId);
        if (last == null) return true;
        return Duration.between(last, Instant.now()).getSeconds() >= metadataSyncIntervalSeconds;
    }

    private void broadcastPipeline(Long jobId, Long mappingId, SyncPipelineState pipeline) {
        if (webSocketNotificationService == null || jobId == null || pipeline == null) {
            return;
        }
        Map<String, Object> traffic = providerRateMeter != null ? providerRateMeter.snapshotMap() : Map.of();
        webSocketNotificationService.notifyPipeline(
                jobId,
                mappingId,
                pipeline.getCurrentStageId(),
                pipeline.currentLabel(),
                pipeline.toMap(),
                traffic
        );
    }

    private static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        if (durationMs < 60_000) {
            return String.format("%.1fs", durationMs / 1000.0);
        }
        long minutes = durationMs / 60_000;
        long seconds = (durationMs % 60_000) / 1000;
        if (durationMs < 3_600_000) {
            return minutes + "m " + seconds + "s";
        }
        long hours = durationMs / 3_600_000;
        long remMinutes = (durationMs % 3_600_000) / 60_000;
        return hours + "h " + remMinutes + "m " + seconds + "s";
    }

    private static String formatCount(int n) {
        return String.format("%,d", n);
    }
}
