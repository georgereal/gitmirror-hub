package com.gitutility.service;

/**
 * Cooperative pause — pair checkpoints are preserved; operator can resume the same job.
 */
public class JobPausedException extends RuntimeException {

    private final String jobId;

    public JobPausedException(String jobId) {
        super("Job #" + jobId + " paused by operator");
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }
}
