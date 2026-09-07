package com.gitutility.model.entity;

import com.gitutility.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "scm_provider_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubAppConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- GitHub Settings ---
    @Column(nullable = false)
    @Builder.Default
    private String authType = "PERSONAL_ACCESS_TOKEN"; // "GITHUB_APP" or "PERSONAL_ACCESS_TOKEN"

    private String appId;
    private String clientId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String clientSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 10000)
    private String privateKeyPem;

    private String installationId;

    /** GitHub App slug from GET /app (e.g. {@code gitmirror-hub}). */
    private String appSlug;

    /** Actions actor login for this App (typically {@code slug[bot]}). */
    private String botLogin;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String webhookSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String defaultPatToken;

    // --- GitHub Enterprise Server (GHES) Settings ---
    private String ghesHostUrl; // e.g. "https://github.internal.acme.com"

    @Builder.Default
    private String ghesAuthType = "PERSONAL_ACCESS_TOKEN"; // "GITHUB_APP" or "PERSONAL_ACCESS_TOKEN"

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String ghesPatToken;

    private String ghesAppId;
    private String ghesClientId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String ghesClientSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 10000)
    private String ghesPrivateKeyPem;

    private String ghesInstallationId;

    /** GHES App slug from GET {host}/api/v3/app. */
    private String ghesAppSlug;

    /** GHES Actions actor login (typically {@code slug[bot]}). */
    private String ghesBotLogin;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String ghesWebhookSecret;

    // --- GitLab Settings ---
    @Builder.Default
    private String gitlabHostUrl = "https://gitlab.com";

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String gitlabAccessToken;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String gitlabWebhookSecret;

    // --- Bitbucket Settings ---
    private String bitbucketWorkspace;
    
    @Builder.Default
    private String bitbucketAuthType = "OAUTH2";

    @Builder.Default
    private String bitbucketUsername = "x-token-auth";

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String bitbucketAccessToken;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String bitbucketWebhookSecret;

    // --- Cursor Origin Settings ---
    @Builder.Default
    private String originHostUrl = "https://origin.cursor.com";

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String originAccessToken;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String originWebhookSecret;

    // --- Generic / Azure DevOps Settings ---
    private String genericUsername;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String genericAccessToken;

    @Column(nullable = false)
    @Builder.Default
    private boolean configured = false;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
