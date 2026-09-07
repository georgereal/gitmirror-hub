package com.gitutility.service;

/**
 * Thrown when an operator cancels a running sync. Must be ACK'd (not retried / DLQ'd).
 */
public class JobCancelledException extends RuntimeException {

    private final Long jobId;

    public JobCancelledException(Long jobId) {
        super("Job #" + jobId + " cancelled by operator");
        this.jobId = jobId;
    }

    public Long getJobId() {
        return jobId;
    }
}
