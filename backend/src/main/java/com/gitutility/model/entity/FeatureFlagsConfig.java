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

/**
 * Singleton product feature toggles. Seeded from env on first insert; Settings UI is source of truth after that.
 */
@Entity
@Table(name = "feature_flags")
@Document(collection = "feature_flags")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlagsConfig implements WritePreparer {

    @Id
    private String id;

    /** Allow PUBLIC visibility / anonymous HTTPS source in pair UI and API. */
    @Column(nullable = false)
    @Builder.Default
    private boolean publicReposEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean providerGitlabEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean providerBitbucketEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean providerOriginEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean providerGenericEnabled = false;

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
