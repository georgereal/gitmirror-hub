package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepoOption {
    private String id;
    private String name;
    private String fullName;
    private String cloneUrl;
    private String htmlUrl;
    private String defaultBranch;
    private boolean isPrivate;
    private boolean canPush;
    private boolean canPull;
    private boolean isAdmin;
    private boolean hasWriteAccess;
    private boolean hasAdminAccess;
    private String owner;
    @Builder.Default
    private String provider = "GITHUB"; // GITHUB, GITLAB, BITBUCKET, ORIGIN, GENERIC
    private String namespace;
    private String description;
    /** GitHub/GHES credential this search result was loaded with. */
    private Long credentialId;
}
