package com.gitutility.model.dto;

import com.gitutility.model.enums.TriggerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncEventMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;
    private Long jobId;
    private Long mappingId;
    private String pairName;
    private String sourceRepoUrl;
    private String targetRepoUrl;
    private String tokenA;
    private String tokenB;
    private Long sourceCredentialId;
    private Long targetCredentialId;
    private String ref;
    private String branch;
    private String beforeSha;
    private String afterSha;
    private String commitMessage;
    private String author;
    private TriggerType triggerType;
    @Builder.Default
    private int attemptCount = 1;
    @Builder.Default
    private int maxAttempts = 3;
    @Builder.Default
    private Instant enqueuedAt = Instant.now();
    private String traceId;
    /** When true, a non-fast-forward trunk push overwrites destination instead of isolating. */
    @Builder.Default
    private boolean overwriteFromSource = false;
    /** When true, always fetch from source even if local packs exist (start-fresh full sync). */
    @Builder.Default
    private boolean forceSourceFetch = false;
}
