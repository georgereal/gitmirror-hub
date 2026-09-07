package com.gitutility.model.entity;

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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnmappedWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Builder.Default
    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    @PrePersist
    @PreUpdate
    protected void onPersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (commitMessage != null && commitMessage.length() > 16_000) {
            commitMessage = commitMessage.substring(0, 15_999) + "\u2026";
        }
        if (details != null && details.length() > 2000) {
            details = details.substring(0, 1999) + "\u2026";
        }
    }
}
