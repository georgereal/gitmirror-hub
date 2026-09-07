package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DlqMessageDto {
    private String messageId;
    private Long jobId;
    private String pairName;
    private String sourceRepo;
    private String targetRepo;
    private String branch;
    private String commitSha;
    private String commitMessage;
    private int attemptCount;
    private String deathReason;
    private Instant deadLetteredAt;
    private Map<String, Object> headers;
    private SyncEventMessage payload;
}
