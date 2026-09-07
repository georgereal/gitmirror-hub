package com.gitutility.model.entity;

import com.gitutility.model.enums.ConflictKind;
import com.gitutility.model.enums.ConflictStatus;
import com.gitutility.model.enums.TrunkConflictPolicy;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "sync_conflicts", indexes = {
        @Index(name = "idx_sync_conflicts_mapping_status", columnList = "mappingId, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mappingId;

    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConflictKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConflictStatus status = ConflictStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TrunkConflictPolicy policyApplied;

    @Column(length = 512)
    private String refName;

    @Column(length = 64)
    private String sourceSha;

    @Column(length = 64)
    private String destSha;

    @Column(length = 512)
    private String isolatedBranch;

    private Long conflictPrNumber;

    @Column(length = 512)
    private String destRepo;

    @Column(length = 1000)
    private String originTitle;

    @Column(length = 1000)
    private String replicaTitle;

    @Column(columnDefinition = "CLOB")
    private String message;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant resolvedAt;
}
