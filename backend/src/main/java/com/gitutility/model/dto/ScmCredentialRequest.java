package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmCredentialRequest {
    private String label;
    private String provider;
    private String hostUrl;
    private String authMode;
    private String appId;
    private String clientId;
    private String clientSecret;
    private String privateKeyPem;
    /** Legacy singular; normalized into {@link #installationIds} when that list is absent. */
    private String installationId;
    /** Selected App installation ids (multi-select). At least one required for GitHub App. */
    private java.util.List<String> installationIds;
    private String webhookSecret;
    private String patToken;
    private Boolean enabled;
}
