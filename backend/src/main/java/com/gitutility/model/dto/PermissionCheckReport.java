package com.gitutility.model.dto;

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
    private boolean isPrivate;
    private int httpStatusCode;
    private String message;

    /** PUBLIC = anonymous HTTPS read succeeded; AUTHENTICATED = credentials were required. */
    private String accessMode;

    private PermissionsDetail permissions;

    @Builder.Default
    private List<String> passedChecks = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> errors = new ArrayList<>();

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
