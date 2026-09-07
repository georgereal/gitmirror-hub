package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubAppConfigRequest {
    // GitHub
    private String authType; // GITHUB_APP or PERSONAL_ACCESS_TOKEN
    private String appId;
    private String clientId;
    private String clientSecret;
    private String privateKeyPem;
    private String installationId;
    private String webhookSecret;
    private String defaultPatToken;

    // GitHub Enterprise Server (GHES)
    private String ghesHostUrl;
    private String ghesAuthType;
    private String ghesPatToken;
    private String ghesAppId;
    private String ghesClientId;
    private String ghesClientSecret;
    private String ghesPrivateKeyPem;
    private String ghesInstallationId;
    private String ghesWebhookSecret;

    // GitLab
    private String gitlabHostUrl;
    private String gitlabAccessToken;
    private String gitlabWebhookSecret;

    // Bitbucket
    private String bitbucketWorkspace;
    private String bitbucketAuthType;
    private String bitbucketUsername;
    private String bitbucketAccessToken;
    private String bitbucketWebhookSecret;

    // Cursor Origin
    private String originHostUrl;
    private String originAccessToken;
    private String originWebhookSecret;

    // Generic Git / Azure DevOps
    private String genericUsername;
    private String genericAccessToken;
}
