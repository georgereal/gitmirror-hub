package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmInstallationOption {
    private String installationId;
    private String accountLogin;
    private String accountType;
    private String repositorySelection;
    private String htmlUrl;
}
