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

@Entity
@Table(name = "ref_origins", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ref_origins_mapping_ref", columnNames = {"mappingId", "refName"})
})
@Document(collection = "ref_origins")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefOrigin implements WritePreparer {

    @Id
    private String id;

    @Column(nullable = false)
    private String mappingId;

    @Column(nullable = false, length = 512)
    private String refName;

    /** A or B — the pair side that first successfully pushed this head. */
    @Column(nullable = false, length = 8)
    private String originSide;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
