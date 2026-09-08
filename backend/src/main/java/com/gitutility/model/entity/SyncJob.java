package com.gitutility.model.entity;

import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "sync_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long mappingId;

    private String pairName;

    @Column(length = 500)
    private String sourceRepo;

    @Column(length = 500)
    private String targetRepo;

    private String ref;

    private String branch;

    @Column(length = 100)
    private String commitSha;

    /** Git squash-merge bodies routinely exceed VARCHAR(1000); persist as CLOB. */
    @Column(columnDefinition = "CLOB")
    private String commitMessage;

    private String author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private SyncStatus status = SyncStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private TriggerType triggerType = TriggerType.WEBHOOK;

    private String queueMessageId;

    @Builder.Default
    private int attemptCount = 0;

    @Builder.Default
    private int maxAttempts = 3;

    private Long durationMs;

    private Instant startedAt;

    private Instant completedAt;

    @Column(length = 4000)
    private String errorMessage;

    @Column(length = 2000)
    private String summaryMessage;

    private Integer branchesCount;
    private Integer tagsCount;
    private Integer lfsObjectsCount;
    private Integer prsSyncedCount;
    private Integer releasesCount;

    private Long bytesTransferred;
    private Integer objectsReceived;
    private Long lfsBytes;
    private Long gitReadBytes;
    private Long gitWriteBytes;
    private Integer lfsSyncedCount;
    private String sourceAccessMode;

    @Column(length = 4000)
    private String rejectedPushRefs;

    @Column(length = 8000)
    private String pipelineJson;

    /** Next pipeline stage to execute when this job is resumed (job-local cursor). */
    @Column(length = 64)
    private String resumeStageId;

    /** Job-scoped partial progress (push ledger, LFS OIDs) — not derived from git at resume time. */
    @Column(columnDefinition = "CLOB")
    private String stageProgressJson;

    private Integer restCallCount;
    private Double restCallsPerMinute;
    private Integer graphqlCallCount;
    private Integer graphqlPointsUsed;
    private Integer graphql429Count;
    /** Git LFS Batch API calls ({@code /info/lfs}) attributed to this run. */
    private Integer lfsApiCallCount;
    /** LFS object download/upload HTTP requests (media/CDN hosts) for this run. */
    private Integer lfsTransferHttpCount;
    private Integer gitHttpFetchCount;
    private Integer gitHttpPushBatchCount;
    private Integer rateLimitRemaining;
    private Integer rateLimitLimit;
    private Integer rateLimit429Count;
    private String providerTrafficProvider;

    /** Job-scoped call-volume samples (REST / GraphQL / Git) — not shared quota remaining. */
    @Column(columnDefinition = "CLOB")
    private String providerTrafficSeriesJson;

    private Double gitPushPerMinute;
    private Double gitFetchPerMinute;
    private Integer gitHttpThrottleCount;

    /** Hub instance currently executing this job (best-effort). */
    @Column(length = 255)
    private String workerInstanceId;

    @Column(length = 1000)
    private String skipReason;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @PrePersist
    @PreUpdate
    protected void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        commitMessage = clip(commitMessage, 16_000);
        errorMessage = clip(errorMessage, 4000);
        summaryMessage = clip(summaryMessage, 2000);
        skipReason = clip(skipReason, 1000);
        rejectedPushRefs = clip(rejectedPushRefs, 4000);
        pipelineJson = clip(pipelineJson, 8000);
        resumeStageId = clip(resumeStageId, 64);
        stageProgressJson = clip(stageProgressJson, 64_000);
        providerTrafficSeriesJson = clip(providerTrafficSeriesJson, 64_000);
    }

    static String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 1) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 1) + "\u2026";
    }
}
