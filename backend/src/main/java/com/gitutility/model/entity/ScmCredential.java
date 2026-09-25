package com.gitutility.model.entity;

import com.gitutility.persistence.Ids;
import com.gitutility.security.Encrypted;
import com.gitutility.security.EncryptedStringConverter;
import org.springframework.data.mongodb.core.mapping.Document;
import com.gitutility.persistence.store.WritePreparer;
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
@Document(collection = "scm_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmCredential implements WritePreparer {

    @Id
    private String id;

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
    @Encrypted
    @Column(length = 2000)
    private String clientSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Encrypted
    @Column(length = 10000)
    private String privateKeyPem;

    /**
     * Primary / display installation id (first of the selected list).
     * Never auto-filled from installations[0] — operator must select.
     */
    private String installationId;

    /**
     * JSON array of selected installation ids for this App card, e.g. {@code ["123","456"]}.
     * New installs are not auto-included until the operator lists and selects them.
     */
    @Column(columnDefinition = "CLOB")
    private String installationIdsJson;

    /** Org or user login for the primary installation (cached from GitHub). */
    private String accountLogin;

    /** {@code Organization} or {@code User}. */
    private String accountType;

    /**
     * GitHub Enterprise Cloud slug for {@code /enterprises/{slug}/rulesets}.
     * Empty on GHES, where org rulesets are the top of the tree.
     */
    @Column(length = 120)
    private String enterpriseSlug;

    /** {@code all} or {@code selected}. */
    private String repositorySelection;

    private String appSlug;
    private String botLogin;

    @Convert(converter = EncryptedStringConverter.class)
    @Encrypted
    @Column(length = 2000)
    private String webhookSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Encrypted
    @Column(length = 2000)
    private String patToken;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
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
