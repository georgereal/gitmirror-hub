package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRepoRequest {
    private String repoUrl;
    private String name;
    private String owner;
    /** User | Organization — from App installation; drives /user/repos vs /orgs/{owner}/repos. */
    private String accountType;
    @Builder.Default
    private Boolean isPrivate = true;
    /**
     * {@code public}, {@code private}, or {@code internal}. When set, this wins over {@link #isPrivate}.
     * GitHub accepts {@code visibility} on create; internal is an organization visibility.
     */
    private String visibility;
    private String description;

    public boolean isPrivateRepo() {
        if (visibility != null && !visibility.isBlank()) {
            return !"public".equalsIgnoreCase(visibility.trim());
        }
        return isPrivate == null || isPrivate;
    }

    /** Lower-case visibility sent to the SCM create API. */
    public String resolvedVisibility() {
        if (visibility != null && !visibility.isBlank()) {
            String v = visibility.trim().toLowerCase(java.util.Locale.ROOT);
            if ("public".equals(v) || "private".equals(v) || "internal".equals(v)) {
                return v;
            }
        }
        return isPrivateRepo() ? "private" : "public";
    }

    public String getOrg() {
        return owner;
    }

    private String credentialId;

    public void inferIdentityFromUrl() {
        if (repoUrl == null || repoUrl.isBlank()) {
            return;
        }
        String trimmed = repoUrl.trim().replaceAll("\\.git$", "").replaceAll("/+$", "");
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        if (name == null || name.isBlank()) {
            name = trimmed.substring(slash + 1);
        }
        if (owner == null || owner.isBlank()) {
            int prev = trimmed.lastIndexOf('/', slash - 1);
            if (prev >= 0) {
                owner = trimmed.substring(prev + 1, slash);
            }
        }
    }
}
