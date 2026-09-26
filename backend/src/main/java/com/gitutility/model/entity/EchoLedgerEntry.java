package com.gitutility.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Shared echo marker so any pod can recognize a push, ref delete, or pull request
 * this Hub just wrote. The id is a hash of the normalized repo key and token, so a
 * second write refreshes the expiry instead of inserting a duplicate.
 */
@Entity
@Table(name = "echo_ledger")
@Document(collection = "echo_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EchoLedgerEntry {

    @Id
    @org.springframework.data.annotation.Id
    @Column(length = 64)
    private String id;

    @Column(name = "repo_key", nullable = false, length = 512)
    private String repoKey;

    @Column(nullable = false, length = 512)
    private String token;

    /** Ref-tip SHA, or the head SHA recorded with a pull-request opened marker. */
    @Column(length = 64)
    private String payload;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static String idFor(String repoKey, String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((repoKey + "\n" + token).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
