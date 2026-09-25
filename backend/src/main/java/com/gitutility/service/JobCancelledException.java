package com.gitutility.service;

/**
 * Thrown when an operator cancels a running sync. Must be ACK'd (not retried / DLQ'd).
 */
public class JobCancelledException extends RuntimeException {

    private final String jobId;

    public JobCancelledException(String jobId) {
        super("Job #" + jobId + " cancelled by operator");
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }
}
