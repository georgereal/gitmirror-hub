package com.gitutility.model.entity;

import com.gitutility.persistence.Ids;
import com.gitutility.persistence.store.WritePreparer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Entity
@Table(name = "failover_parked_event")
@Document(collection = "failover_parked_event")
@CompoundIndex(name = "parked_delivery", def = "{'mappingId': 1, 'deliveryId': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailoverParkedEvent implements WritePreparer {

    @Id
    private String id;

    @Column(nullable = false)
    private String mappingId;

    private String deliveryId;
    private String eventType;
    private String repoUrl;
    private String ref;
    private String beforeSha;
    private String afterSha;

    @Lob
    private String rawPayload;

    @Column(length = 80)
    private String reason;

    /** PARKED or APPLIED */
    @Column(length = 20)
    @Builder.Default
    private String status = "PARKED";

    private Instant receivedAt;
    private Instant parkedAt;
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        if (parkedAt == null) {
            parkedAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }
}
