package com.gitutility.model.entity;

import com.gitutility.model.enums.LogLevel;
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
@Table(name = "sync_audit_logs", indexes = {
    @Index(name = "idx_sync_audit_job_id", columnList = "jobId")
})
@Document(collection = "sync_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncAuditLog implements WritePreparer {

    @Id
    private String id;

    @Column(nullable = false)
    private String jobId;

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
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
