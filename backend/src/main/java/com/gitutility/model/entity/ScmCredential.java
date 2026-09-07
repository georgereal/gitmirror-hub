package com.gitutility.model.entity;

import com.gitutility.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One usable GitHub.com or GHES identity: either a GitHub App installation or a PAT.
 * Auth mode is exclusive — App rows never carry a PAT and PAT rows never mint install tokens.
 */
@Entity
@Table(name = "scm_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String label;

    /** {@code GITHUB} or {@code GITHUB_ENTERPRISE}. */
    @Column(nullable = false, length = 40)
    private String provider;

    /** Cloud is {@code https://github.com}; GHES is this row's appliance URL. */
    @Column(nullable = false, length = 512)
    private String hostUrl;

    /** {@code GITHUB_APP} or {@code PERSONAL_ACCESS_TOKEN}. */
    @Column(nullable = false, length = 40)
    private String authMode;

    private String appId;
    private String clientId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String clientSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 10000)
    private String privateKeyPem;

    /** Required when {@code authMode=GITHUB_APP}. Never auto-filled from installations[0]. */
    private String installationId;

    /** Org or user login the installation is on (cached from GitHub). */
    private String accountLogin;

    /** {@code Organization} or {@code User}. */
    private String accountType;

    /** {@code all} or {@code selected}. */
    private String repositorySelection;

    private String appSlug;
    private String botLogin;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String webhookSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String patToken;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isGitHubApp() {
        return "GITHUB_APP".equalsIgnoreCase(authMode);
    }

    public boolean isPat() {
        return "PERSONAL_ACCESS_TOKEN".equalsIgnoreCase(authMode);
    }

    public boolean isEnterprise() {
        return "GITHUB_ENTERPRISE".equalsIgnoreCase(provider);
    }
}
