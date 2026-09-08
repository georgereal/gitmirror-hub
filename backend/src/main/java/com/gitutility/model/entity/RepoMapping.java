package com.gitutility.model.entity;

import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "repo_mappings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String repoAUrl;

    @Column(nullable = false)
    private String repoBUrl;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String tokenA;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String tokenB;

    @Column(nullable = false)
    @Builder.Default
    private String branchPattern = "*";

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
    private String webhookSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SyncDirection syncDirection = SyncDirection.BIDIRECTIONAL;

    /**
     * How non-fast-forward updates on shared trunk branches are handled.
     * {@code ISOLATE} (default) never force-overwrites destination tips.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private com.gitutility.model.enums.TrunkConflictPolicy trunkConflictPolicy =
            com.gitutility.model.enums.TrunkConflictPolicy.ISOLATE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private com.gitutility.model.enums.StorageTier storageTier = com.gitutility.model.enums.StorageTier.AUTO_LRU;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Instant lastSyncAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private SyncStatus lastSyncStatus;

    /**
     * Cached result of whether the source remote can be fetched anonymously.
     * Null = unknown (probe public-first); true = skip credentials; false = skip anonymous probe.
     */
    private Boolean sourcePublicRead;

    /** SCM provider key for repo A (GITHUB, GITLAB, BITBUCKET, ORIGIN, GENERIC). */
    @Column(length = 50)
    private String sourceProvider;

    /** SCM provider key for repo B. */
    @Column(length = 50)
    private String targetProvider;

    /** GitHub/GHES credential used when repo A was picked. */
    private Long sourceCredentialId;

    /** GitHub/GHES credential used when repo B was picked. */
    private Long targetCredentialId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private com.gitutility.model.enums.RepoVisibility sourceVisibility = com.gitutility.model.enums.RepoVisibility.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private com.gitutility.model.enums.RepoVisibility targetVisibility = com.gitutility.model.enums.RepoVisibility.UNKNOWN;

    /**
     * Resume state for interrupted bootstrap pushes: one {@code ref=sha} line per successfully
     * pushed ref. Cleared when a full mirror completes. Used so a connection-reset retry
     * skips refs already at the local SHA instead of restarting a giant wildcard push.
     */
    @Column(columnDefinition = "CLOB")
    private String completedPushRefs;

    /** Resume checkpoint for interrupted full-mirror runs (PUSH_DONE, LFS_DISCOVERY_DONE, etc.). */
    @Column(length = 40)
    private String syncCheckpointStage;

    @Column(columnDefinition = "CLOB")
    private String completedLfsOids;

    @Column(columnDefinition = "CLOB")
    private String discoveredLfsOids;

    /**
     * SHA-1 ObjectIds of branch tips included in the last LFS ObjectWalk.
     * Next discovery uses these as {@code markUninteresting} (rev-list --not).
     */
    @Column(columnDefinition = "CLOB")
    private String lfsScannedTipOids;

    /** SHA-256 of sorted {@code ref=sha} source heads/tags/notes from the last successful git phase. */
    @Column(length = 64)
    private String lastSourceTipFingerprint;

    /** SHA-256 of sorted dest advertisement heads/tags from the last successful git phase. */
    @Column(length = 64)
    private String lastDestTipFingerprint;

    /** When a full (or GraphQL-delta) open-PR listing completed successfully. */
    private Instant lastPrListCompletedAt;

    /** When release metadata last completed successfully. */
    private Instant lastReleaseSyncAt;

    /** Source PR numbers whose pull-request head refs recently missed on GitHub (JSON). */
    @Column(columnDefinition = "CLOB")
    private String forkPrMissJson;

    /** Last known git mirror stats (persisted when git phases finish, even if PR metadata is still pending). */
    private Long lastMirrorJobId;
    private Instant lastMirrorStatsAt;
    private Integer lastMirrorBranchesCount;
    private Integer lastMirrorTagsCount;
    private Integer lastMirrorLfsObjects;
    private Long lastMirrorBytesTransferred;

    /** JSON snapshot of card-level sync summary (branches/LFS/PR/tags counts). */
    @Column(columnDefinition = "CLOB")
    private String diffSnapshotJson;

    private Instant diffSnapshotAt;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
