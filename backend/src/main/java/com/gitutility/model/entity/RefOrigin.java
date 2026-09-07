package com.gitutility.model.entity;

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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefOrigin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mappingId;

    @Column(nullable = false, length = 512)
    private String refName;

    /** A or B — the pair side that first successfully pushed this head. */
    @Column(nullable = false, length = 8)
    private String originSide;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
