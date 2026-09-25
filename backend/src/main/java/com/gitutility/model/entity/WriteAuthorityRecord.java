package com.gitutility.model.entity;

import com.gitutility.persistence.Ids;
import com.gitutility.persistence.store.WritePreparer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One write/read placement: a pair side, an organization, or an enterprise.
 * The record key is stable so a second save updates the same row.
 */
@Entity
@Table(name = "write_authority")
@Document(collection = "write_authority")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WriteAuthorityRecord implements WritePreparer {

    @Id
    @org.springframework.data.annotation.Id
    @Column(length = 32)
    private String id;

    @Indexed(unique = true)
    @Column(name = "record_key", nullable = false, unique = true, length = 220)
    private String recordKey;

    /** {@code PAIR}, {@code ORG}, or {@code ENTERPRISE}. */
    @Column(nullable = false, length = 20)
    private String subject;

    @Column(length = 32)
    private String mappingId;

    /** {@code A} or {@code B} for a pair side. */
    @Column(length = 8)
    private String side;

    @Column(length = 32)
    private String credentialId;

    @Column(length = 120)
    private String orgLogin;

    @Column(length = 120)
    private String enterpriseSlug;

    /** {@code linked} or {@code independent}. Pair rows only. */
    @Column(length = 20)
    private String linkMode;

    /** {@code repo}, {@code org}, or {@code enterprise}. */
    @Column(length = 20)
    private String scope;

    /** {@code this_repo} or {@code all_repos}. */
    @Column(length = 20)
    private String target;

    /** {@code write} or {@code readonly}. */
    @Column(length = 20)
    private String access;

    private Long rulesetId;

    /** {@code active} or {@code disabled}. */
    @Column(length = 20)
    private String enforcement;

    @Column(length = 120)
    private String rulesetName;

    @Column(length = 300)
    private String repoFullName;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    @Override
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        this.updatedAt = Instant.now();
    }

    public static String pairKey(String mappingId, String side) {
        return "pair:" + mappingId + ":" + side;
    }

    public static String orgKey(String credentialId, String orgLogin) {
        return "org:" + credentialId + ":" + orgLogin;
    }

    public static String enterpriseKey(String credentialId, String slug) {
        return "enterprise:" + credentialId + ":" + slug;
    }

    public static String repoKey(String credentialId, String repoFullName) {
        String name = repoFullName == null ? "" : repoFullName.trim().toLowerCase();
        return "repo:" + credentialId + ":" + name;
    }
}
