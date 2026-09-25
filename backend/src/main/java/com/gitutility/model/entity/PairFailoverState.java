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

@Entity
@Table(name = "pair_failover_state")
@Document(collection = "pair_failover_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PairFailoverState implements WritePreparer {

    @Id
    private String id;

    @Indexed(unique = true)
    @Column(nullable = false, unique = true)
    private String mappingId;

    /** STEADY, FAILOVER, FAILBACK */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String phase = "STEADY";

    @Column(length = 8)
    private String activePrimary;

    /** APPLIED, PENDING, NONE */
    @Column(length = 20)
    @Builder.Default
    private String peerLock = "NONE";

    @Column(length = 16)
    @Builder.Default
    private String sideA = "UNKNOWN";

    @Column(length = 16)
    @Builder.Default
    private String sideB = "UNKNOWN";

    private Instant sideACheckedAt;
    private Instant sideBCheckedAt;

    @Column(length = 500)
    private String sideAError;

    @Column(length = 500)
    private String sideBError;

    @Column(length = 20)
    private String priorDirection;

    /** ORG, ENTERPRISE, or REPO. Heartbeat retries this scope while the lock is pending. */
    @Column(length = 20)
    private String lockScope;

    @Column(length = 32)
    private String lockCredentialId;

    @Column(length = 120)
    private String lockTarget;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        this.updatedAt = Instant.now();
    }
}
