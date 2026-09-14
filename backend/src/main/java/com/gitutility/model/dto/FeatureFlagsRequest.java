package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlagsRequest {
    private Boolean publicReposEnabled;
    private Boolean providerGitlabEnabled;
    private Boolean providerBitbucketEnabled;
    private Boolean providerOriginEnabled;
    private Boolean providerGenericEnabled;
}
