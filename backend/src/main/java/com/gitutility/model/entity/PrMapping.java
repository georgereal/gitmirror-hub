package com.gitutility.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "pr_mappings", indexes = {
    @Index(name = "idx_prmapping_pair_source", columnList = "mappingId, sourcePrNumber")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long mappingId;

    @Column(nullable = false)
    private String sourceRepo;

    @Column(nullable = false)
    private String targetRepo;

    @Column(nullable = false)
    private Long sourcePrNumber;

    /** Null while fork PR tip objects are cached for DR but no dest GitHub PR exists yet. */
    private Long targetPrNumber;

    private String headBranch;
    private String baseBranch;
    private String title;
    /** open, closed, merged, or objects_cached (fork tip stored; dest PR not created). */
    private String state;

    /** Dest-only head materialized from {@code refs/pull/N/head} for a fork PR. Never reverse-sync to origin. */
    @Builder.Default
    private boolean forkPrHead = false;

    /** A or B — which pair side originated this pull request. */
    @Column(length = 8)
    private String originSide;

    /** Last title written to the replica PR (CAS token for edited webhooks). */
    @Column(length = 1000)
    private String lastPushedTitle;

    /** Last body written to the replica PR (CAS token for edited webhooks). */
    @Column(columnDefinition = "CLOB")
    private String lastPushedBody;

    private Instant lastPushedAt;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
