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
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Singleton switches for pair metadata. Missing row means every switch is on.
 * Git ref push stays on regardless of these flags.
 */
@Entity
@Table(name = "metadata_sync_settings")
@Document(collection = "metadata_sync_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataSyncSettings implements WritePreparer {

    @Id
    private String id;

    @Column(nullable = false)
    @Builder.Default
    private boolean pullRequestsEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean releasesEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean ciChecksEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean lfsEnabled = true;

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
