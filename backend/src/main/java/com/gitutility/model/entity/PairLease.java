package com.gitutility.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "pair_leases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairLease {

    @Id
    @Column(name = "mapping_id")
    private Long mappingId;

    @Column(name = "owner_instance", nullable = false, length = 255)
    private String ownerInstance;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
