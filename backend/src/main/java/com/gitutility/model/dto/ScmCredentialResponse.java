package com.gitutility.model.dto;

import com.gitutility.model.entity.ScmCredential;
import com.gitutility.security.CryptoService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmCredentialResponse {
    private Long id;
    private String label;
    private String provider;
    private String hostUrl;
    private String authMode;
    private String appId;
    private String clientId;
    private String installationId;
    private String accountLogin;
    private String accountType;
    private String repositorySelection;
    private String appSlug;
    private String botLogin;
    private boolean hasPrivateKey;
    private boolean hasPatToken;
    private boolean hasWebhookSecret;
    private boolean hasClientSecret;
    private String clientSecretMasked;
    private String webhookSecretMasked;
    private String webhookPath;
    private boolean enabled;
    private Instant updatedAt;

    public static ScmCredentialResponse fromEntity(ScmCredential entity) {
        if (entity == null) {
            return null;
        }
        String pathPrefix = entity.isEnterprise() ? "/webhook/ghes/credential/" : "/webhook/github/credential/";
        return ScmCredentialResponse.builder()
                .id(entity.getId())
                .label(entity.getLabel())
                .provider(entity.getProvider())
                .hostUrl(entity.getHostUrl())
                .authMode(entity.getAuthMode())
                .appId(entity.getAppId())
                .clientId(entity.getClientId())
                .installationId(entity.getInstallationId())
                .accountLogin(entity.getAccountLogin())
                .accountType(entity.getAccountType())
                .repositorySelection(entity.getRepositorySelection())
                .appSlug(entity.getAppSlug())
                .botLogin(entity.getBotLogin())
                .hasPrivateKey(notBlank(entity.getPrivateKeyPem()))
                .hasPatToken(notBlank(entity.getPatToken()))
                .hasWebhookSecret(notBlank(entity.getWebhookSecret()))
                .hasClientSecret(notBlank(entity.getClientSecret()))
                .clientSecretMasked(CryptoService.mask(entity.getClientSecret()))
                .webhookSecretMasked(CryptoService.mask(entity.getWebhookSecret()))
                .webhookPath(pathPrefix + entity.getId())
                .enabled(entity.isEnabled())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
