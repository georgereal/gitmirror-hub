package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Normalized incremental git event shared by Kafka and Rabbit on the webhook bus.
 * The upstream, or {@code webhook-worker-kafka} on Cloudflare, publishes this shape. Hub resolves the pair.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncrementalGitEvent {

    private String provider;
    private String repoUrl;
    private String ref;
    private String beforeSha;
    private String afterSha;
    private String deliveryId;
    /** {@code push}, {@code create}, {@code delete}, {@code pull_request}, {@code release}, {@code status}, or {@code check_run}. */
    private String eventType;
    /** Original webhook body for metadata events. Git push and delete leave this empty. */
    private String rawPayload;
    private String receivedAt;
    /** Set when a busy lease re-queues the same SyncJob. */
    private String jobId;
    @Builder.Default
    private int attempt = 1;
    /** Last failure, kept on the record when it is stored for replay. */
    private String error;

    public int attemptOrOne() {
        return attempt < 1 ? 1 : attempt;
    }
}
