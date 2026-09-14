package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    /** Explicit name — Lombok {@code isPrivate()} otherwise serializes as {@code "private"}. */
    @JsonProperty("isPrivate")
    private boolean isPrivate;
    private boolean canPush;
    private boolean canPull;
    @JsonProperty("isAdmin")
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
    /** Installation that returned this repo (App multi-install cards). */
    private String installationId;
}
