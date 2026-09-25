package com.gitutility.model.entity;

import org.springframework.data.mongodb.core.mapping.Document;
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
@Document(collection = "pair_leases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairLease {

    @Id
    // Spring Data MongoDB ignores the JPA @Id above; this annotation makes mappingId the Mongo _id,
    // so findById(mappingId), save-as-replace, and the unique-key race guard in PairLeaseService all work.
    @org.springframework.data.annotation.Id
    @Column(name = "mapping_id")
    private String mappingId;

    @Column(name = "owner_instance", nullable = false, length = 255)
    private String ownerInstance;

    @Column(name = "job_id")
    private String jobId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
