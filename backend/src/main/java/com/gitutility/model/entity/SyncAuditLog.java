package com.gitutility.model.entity;

import com.gitutility.model.enums.LogLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "sync_audit_logs", indexes = {
    @Index(name = "idx_sync_audit_job_id", columnList = "jobId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LogLevel level = LogLevel.INFO;

    @Column(length = 2000, nullable = false)
    private String message;

    @Column(nullable = false)
    @Builder.Default
    private Instant timestamp = Instant.now();

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
