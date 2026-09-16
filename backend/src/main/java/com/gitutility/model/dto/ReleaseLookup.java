package com.gitutility.model.dto;

import java.util.List;

/**
 * Result of looking up an existing release on a repository by tag.
 * {@code externalId} is the provider's release identifier (GitHub numeric id as string,
 * GitLab tag name, ...) that must be round-tripped to update/delete calls.
 */
public record ReleaseLookup(
        boolean exists,
        String externalId,
        String tagName,
        String name,
        String body,
        boolean draft,
        boolean prerelease,
        List<String> assetNames) {

    public static ReleaseLookup missing() {
        return new ReleaseLookup(false, null, null, null, null, false, false, List.of());
    }
}