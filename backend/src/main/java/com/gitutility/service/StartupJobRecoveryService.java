package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.SyncJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * On boot: pause sync consumers (no automatic backlog drain) and mark orphan IN_PROGRESS jobs
 * as INTERRUPTED. Operators dispatch selected jobs manually from Queue Manager.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StartupJobRecoveryService {

    private final SimulationService simulationService;
    private final SyncJobRepository syncJobRepository;
    private final WebSocketNotificationService webSocketNotificationService;
    private final JobExecutionStateService jobExecutionStateService;

    @Value("${git-utility.queue.pause-consumers-on-startup:true}")
    private boolean pauseConsumersOnStartup;

    @Value("${git-utility.queue.orphan-job-grace-seconds:120}")
    private int orphanJobGraceSeconds;

    @PostConstruct
    public void onStartup() {
        int interrupted = markOrphanInProgressJobs();
        if (interrupted > 0) {
            log.warn("Marked {} orphan IN_PROGRESS job(s) as INTERRUPTED after server restart", interrupted);
        }
        if (pauseConsumersOnStartup) {
            simulationService.markStartupHold();
            simulationService.pauseConsumer();
            log.info("Sync consumers paused on startup — dispatch selected jobs and resume consumers from Queue Manager");
        }
    }

    int markOrphanInProgressJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(Math.max(30, orphanJobGraceSeconds)));
        List<SyncJob> orphans = syncJobRepository.findByStatus(SyncStatus.IN_PROGRESS);
        int count = 0;
        for (SyncJob job : orphans) {
            if (job.getStartedAt() != null && job.getStartedAt().isAfter(cutoff)) {
                continue;
            }
            job.setStatus(SyncStatus.INTERRUPTED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage("Interrupted by server restart — use Resume to continue from checkpoint");
            if (job.getStartedAt() != null) {
                job.setDurationMs(Math.max(0, Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis()));
            }
            SyncPipelineState pipeline = jobExecutionStateService.loadPipeline(job);
            jobExecutionStateService.captureInterrupted(job, pipeline, jobExecutionStateService.loadStageProgress(job));
            syncJobRepository.save(job);
            webSocketNotificationService.notifyJobUpdated(job);
            count++;
        }
        return count;
    }
}
