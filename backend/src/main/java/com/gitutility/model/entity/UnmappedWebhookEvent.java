package com.gitutility.model.entity;

import com.gitutility.persistence.Ids;
import org.springframework.data.mongodb.core.mapping.Document;
import com.gitutility.persistence.store.WritePreparer;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "unmapped_webhook_events", indexes = {
    @Index(name = "idx_unmapped_received_at", columnList = "receivedAt"),

    @Index(name = "idx_unmapped_repo_name", columnList = "repoFullName")
})
@Document(collection = "unmapped_webhook_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnmappedWebhookEvent implements WritePreparer {

    @Id
    private String id;

    @Column(nullable = false)
    private String provider; // e.g. "github", "gitlab", "bitbucket"

    private String repoFullName; // e.g. "acme/example-repo"

    private String repoUrl; // e.g. "https://github.com/acme/example-repo.git"

    private String eventType; // "push", "pull_request", "ping", "status", etc.

    private String sender; // e.g. "alice"

    private String branch; // e.g. "main"

    private String commitSha; // e.g. "a1b2c3d..."

    @Column(columnDefinition = "CLOB")
    private String commitMessage;

    @Column(nullable = false)
    private String discardReason; // "UNMAPPED_REPOSITORY", "INACTIVE_MAPPING", "NON_BRANCH_REF", "UNSUPPORTED_EVENT", "ECHO_DETECTED"

    @Column(length = 2000)
    private String details;

    /** Original incremental JSON when a Kafka record could not be processed. Replay reads this. */
    @Column(columnDefinition = "CLOB")
    private String payloadJson;

    @Builder.Default
    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    /**
     * Mongo TTL deletes the row when this instant passes. Unreplayed {@code KAFKA_POISON} rows leave it null.
     */
    private Instant expiresAt;

    @PrePersist
    @PreUpdate
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (commitMessage != null && commitMessage.length() > 16_000) {
            commitMessage = commitMessage.substring(0, 15_999) + "\u2026";
        }
        if (details != null && details.length() > 2000) {
            details = details.substring(0, 1999) + "\u2026";
        }
        if (payloadJson != null && payloadJson.length() > 32_000) {
            payloadJson = payloadJson.substring(0, 31_999) + "\u2026";
        }
    }
}
