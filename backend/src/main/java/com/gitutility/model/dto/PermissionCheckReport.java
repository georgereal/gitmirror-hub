package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionCheckReport {
    private boolean valid;
    private String repoFullName;
    private String defaultBranch;
    /**
     * Repo visibility from the SCM. Explicit JSON name required: Lombok's {@code isPrivate()}
     * getter otherwise serializes as {@code "private"}, which the UI never reads.
     */
    @JsonProperty("isPrivate")
    private boolean isPrivate;
    private int httpStatusCode;
    private String message;

    /** PUBLIC = anonymous Access check succeeded; AUTHENTICATED = App/PAT Access check succeeded. */
    private String accessMode;

    /** Credential the check authenticated with. Absent on anonymous public read. */
    private String credentialId;

    /** App installation token the check used. Absent for PATs and anonymous public read. */
    private String installationId;

    /** Account login of {@link #installationId}, so the UI can show the owner that was checked. */
    private String installationLogin;

    /**
     * Provider-reported visibility: PUBLIC, PRIVATE, or INTERNAL.
     * The UI displays this value; it does not infer visibility from {@link #isPrivate}.
     */
    private String visibility;

    private PermissionsDetail permissions;

    @Builder.Default
    private List<String> passedChecks = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /**
     * Destination (WRITE) checks only: {@code true} when the remote repository has zero refs (blank),
     * so the first full mirror will use the bulk mirror bootstrap (single-connection push).
     * {@code null} when unknown (probe not performed / not a destination check).
     */
    private Boolean emptyDestination;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionsDetail {
        private boolean contentsRead;
        private boolean contentsWrite;
        private boolean pullRequests;
        private boolean commitStatuses;
        private boolean webhooks;
        private boolean admin;
    }
}
