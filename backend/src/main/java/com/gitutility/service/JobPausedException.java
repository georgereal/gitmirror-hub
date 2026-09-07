package com.gitutility.service;

/**
 * Cooperative pause — pair checkpoints are preserved; operator can resume the same job.
 */
public class JobPausedException extends RuntimeException {

    private final Long jobId;

    public JobPausedException(Long jobId) {
        super("Job #" + jobId + " paused by operator");
        this.jobId = jobId;
    }

    public Long getJobId() {
        return jobId;
    }
}
