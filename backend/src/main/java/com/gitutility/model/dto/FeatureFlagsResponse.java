package com.gitutility.model.dto;

import com.gitutility.model.entity.FeatureFlagsConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlagsResponse {
    private boolean publicReposEnabled;
    private boolean providerGitlabEnabled;
    private boolean providerBitbucketEnabled;
    private boolean providerOriginEnabled;
    private boolean providerGenericEnabled;
    private Instant updatedAt;

    public static FeatureFlagsResponse fromEntity(FeatureFlagsConfig entity) {
        if (entity == null) {
            return null;
        }
        return FeatureFlagsResponse.builder()
                .publicReposEnabled(entity.isPublicReposEnabled())
                .providerGitlabEnabled(entity.isProviderGitlabEnabled())
                .providerBitbucketEnabled(entity.isProviderBitbucketEnabled())
                .providerOriginEnabled(entity.isProviderOriginEnabled())
                .providerGenericEnabled(entity.isProviderGenericEnabled())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
