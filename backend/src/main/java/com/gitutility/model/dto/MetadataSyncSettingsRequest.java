package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataSyncSettingsRequest {
    private Boolean pullRequestsEnabled;
    private Boolean releasesEnabled;
    private Boolean ciChecksEnabled;
    private Boolean lfsEnabled;
}
