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
@Table(name = "pr_mappings", indexes = {
    @Index(name = "idx_prmapping_pair_source", columnList = "mappingId, sourcePrNumber")
})
@Document(collection = "pr_mappings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrMapping implements WritePreparer {

    @Id
    private String id;

    @Column(nullable = false)
    private String mappingId;

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

    /** Mirrored source PR title — clamped to 1000 chars before write (see {@link #clampColumnLimitsBeforeWrite()}). */
    @Column(length = 1000)
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

    /** Must match the {@code VARCHAR(1000)} width of the title columns in the database. */
    static final int TITLE_COLUMN_LENGTH = 1000;

    /**
     * pr_mappings.title and last_pushed_title are VARCHAR(1000). Oversized source PR titles are
     * clamped here at the entity boundary (JPA invokes this before every INSERT and UPDATE) so a
     * single bad row can never abort the whole PR-mapping batch save.
     */
    @PrePersist
    @PreUpdate
    public void prepareForWrite() {
        if (Ids.isUnset(id)) {
            id = Ids.newId();
        }
        title = clampToColumnLimit(title);
        lastPushedTitle = clampToColumnLimit(lastPushedTitle);
    }

    static String clampToColumnLimit(String value) {
        if (value == null || value.length() <= TITLE_COLUMN_LENGTH) {
            return value;
        }
        return value.substring(0, TITLE_COLUMN_LENGTH);
    }
}
