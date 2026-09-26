package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One probe from the simulation screen. {@code side} is the repository the event
 * arrives on: SOURCE (repo A) or DESTINATION (repo B).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationScenarioRequest {
    private String mappingId;
    /** SOURCE or DESTINATION. */
    private String side;
    /** branch, tag, note, pull_request, release, status, check_run. */
    private String kind;
    /** push, delete, open, edit, close, merge, publish, unpublish, success, failure, pending. */
    private String operation;
    private String refName;
    private String beforeSha;
    private String commitSha;
    private String commitMessage;
    private String authorName;
    private Long pullRequestNumber;
    private String title;
    private String body;
    private String baseBranch;
    private String releaseTag;
    private String releaseName;
    /** Status context or check-run name. */
    private String context;
}
