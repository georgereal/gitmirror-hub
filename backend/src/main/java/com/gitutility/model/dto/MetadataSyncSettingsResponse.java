package com.gitutility.model.dto;

import com.gitutility.model.entity.MetadataSyncSettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataSyncSettingsResponse {
    private boolean pullRequestsEnabled;
    private boolean releasesEnabled;
    private boolean ciChecksEnabled;
    private boolean lfsEnabled;
    private Instant updatedAt;

    public static MetadataSyncSettingsResponse fromEntity(MetadataSyncSettings entity) {
        if (entity == null) {
            return MetadataSyncSettingsResponse.builder()
                    .pullRequestsEnabled(true)
                    .releasesEnabled(true)
                    .ciChecksEnabled(true)
                    .lfsEnabled(true)
                    .build();
        }
        return MetadataSyncSettingsResponse.builder()
                .pullRequestsEnabled(entity.isPullRequestsEnabled())
                .releasesEnabled(entity.isReleasesEnabled())
                .ciChecksEnabled(entity.isCiChecksEnabled())
                .lfsEnabled(entity.isLfsEnabled())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
