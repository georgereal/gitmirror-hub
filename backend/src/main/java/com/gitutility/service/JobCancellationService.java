package com.gitutility.service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cancel registry so a running JGit fetch/push can abort without waiting
 * for the AMQP message to drain. Queued jobs are marked {@code CANCELLED} in the DB;
 * the consumer skips them on pickup.
 */
@Service
public class JobCancellationService {

    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();
    private final Set<Long> pauseRequested = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Thread> runningThreads = new ConcurrentHashMap<>();

    public void requestCancel(Long jobId) {
        if (jobId == null) {
            return;
        }
        cancelRequested.add(jobId);
        pauseRequested.remove(jobId);
        // Do not interrupt the AMQP listener thread. Spring AMQP treats an interrupted
        // consumer as fatal and stops the container, leaving cancelled messages unacked.
        // JGit honors ProgressMonitor.isCancelled() on the next fetch/push tick.
    }

    public void requestPause(Long jobId) {
        if (jobId == null) {
            return;
        }
        pauseRequested.add(jobId);
        cancelRequested.remove(jobId);
    }

    public boolean isCancelRequested(Long jobId) {
        return jobId != null && cancelRequested.contains(jobId);
    }

    public boolean isPauseRequested(Long jobId) {
        return jobId != null && pauseRequested.contains(jobId);
    }

    public void registerRunning(Long jobId) {
        registerRunning(jobId, Thread.currentThread());
    }

    void registerRunning(Long jobId, Thread thread) {
        if (jobId != null && thread != null) {
            runningThreads.put(jobId, thread);
        }
    }

    public void unregisterRunning(Long jobId) {
        if (jobId != null) {
            runningThreads.remove(jobId);
            cancelRequested.remove(jobId);
            pauseRequested.remove(jobId);
        }
    }
}
