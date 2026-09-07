package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyntheticWebhookRequest {
    private Long mappingId;
    private String sourceRepo;
    private String branch;
    private String commitSha;
    private String commitMessage;
    private String authorName;
    private String authorEmail;
}
