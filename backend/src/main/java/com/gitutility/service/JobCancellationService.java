package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cancel/pause registry for in-flight JGit work. Flags are mirrored to {@link SyncJob}
 * so sibling Hub pods honor operator control for jobs they own.
 */
@Service
@RequiredArgsConstructor
public class JobCancellationService {

    private final SyncJobRepository syncJobRepository;

    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Thread> runningThreads = new ConcurrentHashMap<>();

    @Transactional
    public void requestCancel(Long jobId) {
        if (jobId == null) {
            return;
        }
        cancelRequested.add(jobId);
        pauseRequested.remove(jobId);
        syncJobRepository.findById(jobId).ifPresent(job -> {
            job.setCancelRequested(true);
            job.setPauseRequested(false);
            syncJobRepository.save(job);
        });
        // Do not interrupt the AMQP listener thread. Spring AMQP treats an interrupted
        // consumer as fatal and stops the container, leaving cancelled messages unacked.
        // JGit honors ProgressMonitor.isCancelled() on the next fetch/push tick.
    }

    @Transactional
    public void requestPause(Long jobId) {
        if (jobId == null) {
            return;
        }
        pauseRequested.add(jobId);
        cancelRequested.remove(jobId);
        syncJobRepository.findById(jobId).ifPresent(job -> {
            job.setPauseRequested(true);
            job.setCancelRequested(false);
            syncJobRepository.save(job);
        });
    }

    public boolean isCancelRequested(Long jobId) {
        if (jobId == null) {
            return false;
        }
        if (cancelRequested.contains(jobId)) {
            return true;
        }
        return syncJobRepository.findById(jobId).map(SyncJob::isCancelRequested).orElse(false);
    }

    public boolean isPauseRequested(Long jobId) {
        if (jobId == null) {
            return false;
        }
        if (pauseRequested.contains(jobId)) {
            return true;
        }
        return syncJobRepository.findById(jobId).map(SyncJob::isPauseRequested).orElse(false);
    }

    public void registerRunning(Long jobId) {
        registerRunning(jobId, Thread.currentThread());
    }

    void registerRunning(Long jobId, Thread thread) {
        if (jobId != null && thread != null) {
            runningThreads.put(jobId, thread);
        }
    }

    @Transactional
    public void unregisterRunning(Long jobId) {
        if (jobId != null) {
            runningThreads.remove(jobId);
            cancelRequested.remove(jobId);
            pauseRequested.remove(jobId);
            syncJobRepository.findById(jobId).ifPresent(job -> {
                if (job.isCancelRequested() || job.isPauseRequested()) {
                    job.setCancelRequested(false);
                    job.setPauseRequested(false);
                    syncJobRepository.save(job);
                }
            });
        }
    }
}
