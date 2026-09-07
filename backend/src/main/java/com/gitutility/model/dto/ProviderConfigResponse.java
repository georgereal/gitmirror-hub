package com.gitutility.model.dto;

import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.security.CryptoService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Sanitized response for SCM Provider configurations.
 * Masks all PATs, private keys, client secrets, and webhook secrets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfigResponse {

    private Long id;
    private String authType;
    private String appId;
    private String clientId;
    private String installationId;
    private String appSlug;
    private String botLogin;

    // Masked secrets
    private String clientSecretMasked;
    private boolean hasClientSecret;

    private String privateKeyPemMasked;
    private boolean hasPrivateKey;

    private String webhookSecretMasked;
    private boolean hasWebhookSecret;

    private String defaultPatTokenMasked;
    private boolean hasDefaultPatToken;

    // GitHub Enterprise Server (GHES)
    private String ghesHostUrl;
    private String ghesAuthType;
    private String ghesPatTokenMasked;
    private boolean hasGhesPatToken;
    private String ghesAppId;
    private String ghesClientId;
    private String ghesClientSecretMasked;
    private boolean hasGhesClientSecret;
    private String ghesPrivateKeyPemMasked;
    private boolean hasGhesPrivateKey;
    private String ghesInstallationId;
    private String ghesAppSlug;
    private String ghesBotLogin;
    private String ghesWebhookSecretMasked;
    private boolean hasGhesWebhookSecret;

    // GitLab
    private String gitlabHostUrl;
    private String gitlabAccessTokenMasked;
    private boolean hasGitlabAccessToken;
    private String gitlabWebhookSecretMasked;
    private boolean hasGitlabWebhookSecret;

    // Bitbucket
    private String bitbucketWorkspace;
    private String bitbucketAuthType;
    private String bitbucketUsername;
    private String bitbucketAccessTokenMasked;
    private boolean hasBitbucketAccessToken;
    private String bitbucketWebhookSecretMasked;
    private boolean hasBitbucketWebhookSecret;

    // Cursor Origin
    private String originHostUrl;
    private String originAccessTokenMasked;
    private boolean hasOriginAccessToken;
    private String originWebhookSecretMasked;
    private boolean hasOriginWebhookSecret;

    // Generic
    private String genericUsername;
    private String genericAccessTokenMasked;
    private boolean hasGenericAccessToken;

    private boolean configured;
    private Instant updatedAt;

    public static ProviderConfigResponse fromEntity(GitHubAppConfig entity) {
        if (entity == null) return null;

        return ProviderConfigResponse.builder()
                .id(entity.getId())
                .authType(entity.getAuthType())
                .appId(entity.getAppId())
                .clientId(entity.getClientId())
                .installationId(entity.getInstallationId())
                .appSlug(entity.getAppSlug())
                .botLogin(entity.getBotLogin())

                .clientSecretMasked(CryptoService.mask(entity.getClientSecret()))
                .hasClientSecret(entity.getClientSecret() != null && !entity.getClientSecret().isBlank())

                .privateKeyPemMasked(entity.getPrivateKeyPem() != null && !entity.getPrivateKeyPem().isBlank() ? "-----BEGIN RSA PRIVATE KEY----- [SECURELY STORED]" : null)
                .hasPrivateKey(entity.getPrivateKeyPem() != null && !entity.getPrivateKeyPem().isBlank())

                .webhookSecretMasked(CryptoService.mask(entity.getWebhookSecret()))
                .hasWebhookSecret(entity.getWebhookSecret() != null && !entity.getWebhookSecret().isBlank())

                .defaultPatTokenMasked(CryptoService.mask(entity.getDefaultPatToken()))
                .hasDefaultPatToken(entity.getDefaultPatToken() != null && !entity.getDefaultPatToken().isBlank())

                .ghesHostUrl(entity.getGhesHostUrl())
                .ghesAuthType(entity.getGhesAuthType())
                .ghesPatTokenMasked(CryptoService.mask(entity.getGhesPatToken()))
                .hasGhesPatToken(entity.getGhesPatToken() != null && !entity.getGhesPatToken().isBlank())
                .ghesAppId(entity.getGhesAppId())
                .ghesClientId(entity.getGhesClientId())
                .ghesClientSecretMasked(CryptoService.mask(entity.getGhesClientSecret()))
                .hasGhesClientSecret(entity.getGhesClientSecret() != null && !entity.getGhesClientSecret().isBlank())
                .ghesPrivateKeyPemMasked(entity.getGhesPrivateKeyPem() != null && !entity.getGhesPrivateKeyPem().isBlank() ? "-----BEGIN RSA PRIVATE KEY----- [SECURELY STORED]" : null)
                .hasGhesPrivateKey(entity.getGhesPrivateKeyPem() != null && !entity.getGhesPrivateKeyPem().isBlank())
                .ghesInstallationId(entity.getGhesInstallationId())
                .ghesAppSlug(entity.getGhesAppSlug())
                .ghesBotLogin(entity.getGhesBotLogin())
                .ghesWebhookSecretMasked(CryptoService.mask(entity.getGhesWebhookSecret()))
                .hasGhesWebhookSecret(entity.getGhesWebhookSecret() != null && !entity.getGhesWebhookSecret().isBlank())

                .gitlabHostUrl(entity.getGitlabHostUrl())
                .gitlabAccessTokenMasked(CryptoService.mask(entity.getGitlabAccessToken()))
                .hasGitlabAccessToken(entity.getGitlabAccessToken() != null && !entity.getGitlabAccessToken().isBlank())
                .gitlabWebhookSecretMasked(CryptoService.mask(entity.getGitlabWebhookSecret()))
                .hasGitlabWebhookSecret(entity.getGitlabWebhookSecret() != null && !entity.getGitlabWebhookSecret().isBlank())

                .bitbucketWorkspace(entity.getBitbucketWorkspace())
                .bitbucketAuthType(entity.getBitbucketAuthType())
                .bitbucketUsername(entity.getBitbucketUsername())
                .bitbucketAccessTokenMasked(CryptoService.mask(entity.getBitbucketAccessToken()))
                .hasBitbucketAccessToken(entity.getBitbucketAccessToken() != null && !entity.getBitbucketAccessToken().isBlank())
                .bitbucketWebhookSecretMasked(CryptoService.mask(entity.getBitbucketWebhookSecret()))
                .hasBitbucketWebhookSecret(entity.getBitbucketWebhookSecret() != null && !entity.getBitbucketWebhookSecret().isBlank())

                .originHostUrl(entity.getOriginHostUrl())
                .originAccessTokenMasked(CryptoService.mask(entity.getOriginAccessToken()))
                .hasOriginAccessToken(entity.getOriginAccessToken() != null && !entity.getOriginAccessToken().isBlank())
                .originWebhookSecretMasked(CryptoService.mask(entity.getOriginWebhookSecret()))
                .hasOriginWebhookSecret(entity.getOriginWebhookSecret() != null && !entity.getOriginWebhookSecret().isBlank())

                .genericUsername(entity.getGenericUsername())
                .genericAccessTokenMasked(CryptoService.mask(entity.getGenericAccessToken()))
                .hasGenericAccessToken(entity.getGenericAccessToken() != null && !entity.getGenericAccessToken().isBlank())

                .configured(entity.isConfigured())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
