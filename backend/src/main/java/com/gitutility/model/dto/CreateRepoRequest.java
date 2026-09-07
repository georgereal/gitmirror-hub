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
    @Builder.Default
    private Boolean isPrivate = true;
    private String description;

    public boolean isPrivateRepo() {
        return isPrivate == null || isPrivate;
    }

    public String getOrg() {
        return owner;
    }

    private Long credentialId;

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
