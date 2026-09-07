package com.gitutility.model.dto;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.security.CryptoService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoMappingResponse {

    private Long id;
    private String name;
    private String repoAUrl;
    private String repoBUrl;

    private String tokenAMasked;
    private boolean hasTokenA;

    private String tokenBMasked;
    private boolean hasTokenB;

    private String branchPattern;
    private String webhookSecretMasked;
    private boolean hasWebhookSecret;

    private SyncDirection syncDirection;
    private com.gitutility.model.enums.TrunkConflictPolicy trunkConflictPolicy;
    private com.gitutility.model.enums.StorageTier storageTier;
    private boolean active;
    private Instant lastSyncAt;
    private SyncStatus lastSyncStatus;
    private Instant createdAt;
    private String sourceProvider;
    private String targetProvider;
    private Long sourceCredentialId;
    private Long targetCredentialId;
    private com.gitutility.model.enums.RepoVisibility sourceVisibility;
    private com.gitutility.model.enums.RepoVisibility targetVisibility;
    private boolean hasCheckpoint;
    private String syncCheckpointStage;
    private Long lastMirrorJobId;
    private Instant lastMirrorStatsAt;
    private Integer lastMirrorBranchesCount;
    private Integer lastMirrorTagsCount;
    private Integer lastMirrorLfsObjects;
    private Long lastMirrorBytesTransferred;
    private Instant diffSnapshotAt;

    private Integer sourceBranchesCount;
    private Integer destBranchesCount;
    private Integer inSyncBranchesCount;
    private Integer pendingBranchesCount;
    private Integer divergedBranchesCount;
    private Integer prsTotal;
    private Integer prsSynced;
    private Integer lfsTotal;
    private Integer lfsSynced;
    private Integer lfsPending;
    private Integer tagsSourceCount;
    private Integer tagsTargetCount;

    public static RepoMappingResponse fromEntity(RepoMapping entity) {
        if (entity == null) return null;

        PairDiffSnapshot snapshot = PairDiffSnapshot.parseJson(entity.getDiffSnapshotJson());
        int lastMirrorLfs = entity.getLastMirrorLfsObjects() != null ? entity.getLastMirrorLfsObjects() : 0;

        Integer sourceBranches = snapshot != null ? snapshot.getSourceBranchesCount() : entity.getLastMirrorBranchesCount();
        Integer destBranches = snapshot != null ? snapshot.getDestBranchesCount() : null;
        Integer inSyncBranches = snapshot != null ? snapshot.getInSyncBranchesCount() : null;
        Integer pendingBranches = snapshot != null ? snapshot.getPendingBranchesCount() : null;
        Integer divergedBranches = snapshot != null ? snapshot.getDivergedBranchesCount() : null;

        Integer prsTotal = snapshot != null ? snapshot.getPrsTotal() : null;
        Integer prsSynced = snapshot != null ? snapshot.getPrsSynced() : null;

        Integer lfsTotal;
        Integer lfsSynced;
        Integer lfsPending;
        if (snapshot != null) {
            int total = Math.max(snapshot.getLfsTotal(), lastMirrorLfs);
            int synced = Math.max(snapshot.getLfsSynced(), lastMirrorLfs);
            total = Math.max(total, synced);
            lfsTotal = total;
            lfsSynced = Math.min(total, synced);
            lfsPending = Math.max(0, total - synced);
        } else if (entity.getLastMirrorLfsObjects() != null) {
            lfsTotal = lastMirrorLfs;
            lfsSynced = lastMirrorLfs;
            lfsPending = 0;
        } else {
            lfsTotal = null;
            lfsSynced = null;
            lfsPending = null;
        }

        Integer tagsSource = snapshot != null ? snapshot.getTagsSourceCount() : entity.getLastMirrorTagsCount();
        Integer tagsTarget = snapshot != null ? snapshot.getTagsTargetCount() : null;

        return RepoMappingResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .repoAUrl(entity.getRepoAUrl())
                .repoBUrl(entity.getRepoBUrl())
                .tokenAMasked(CryptoService.mask(entity.getTokenA()))
                .hasTokenA(entity.getTokenA() != null && !entity.getTokenA().isBlank())
                .tokenBMasked(CryptoService.mask(entity.getTokenB()))
                .hasTokenB(entity.getTokenB() != null && !entity.getTokenB().isBlank())
                .branchPattern(entity.getBranchPattern())
                .webhookSecretMasked(CryptoService.mask(entity.getWebhookSecret()))
                .hasWebhookSecret(entity.getWebhookSecret() != null && !entity.getWebhookSecret().isBlank())
                .syncDirection(entity.getSyncDirection())
                .trunkConflictPolicy(entity.getTrunkConflictPolicy() != null
                        ? entity.getTrunkConflictPolicy()
                        : com.gitutility.model.enums.TrunkConflictPolicy.ISOLATE)
                .storageTier(entity.getStorageTier() != null ? entity.getStorageTier() : com.gitutility.model.enums.StorageTier.AUTO_LRU)
                .active(entity.isActive())
                .lastSyncAt(entity.getLastSyncAt())
                .lastSyncStatus(entity.getLastSyncStatus())
                .createdAt(entity.getCreatedAt())
                .sourceProvider(entity.getSourceProvider())
                .targetProvider(entity.getTargetProvider())
                .sourceCredentialId(entity.getSourceCredentialId())
                .targetCredentialId(entity.getTargetCredentialId())
                .sourceVisibility(entity.getSourceVisibility())
                .targetVisibility(entity.getTargetVisibility())
                .hasCheckpoint(entity.getSyncCheckpointStage() != null && !entity.getSyncCheckpointStage().isBlank())
                .syncCheckpointStage(entity.getSyncCheckpointStage())
                .lastMirrorJobId(entity.getLastMirrorJobId())
                .lastMirrorStatsAt(entity.getLastMirrorStatsAt())
                .lastMirrorBranchesCount(entity.getLastMirrorBranchesCount())
                .lastMirrorTagsCount(entity.getLastMirrorTagsCount())
                .lastMirrorLfsObjects(entity.getLastMirrorLfsObjects())
                .lastMirrorBytesTransferred(entity.getLastMirrorBytesTransferred())
                .diffSnapshotAt(entity.getDiffSnapshotAt())
                .sourceBranchesCount(sourceBranches)
                .destBranchesCount(destBranches)
                .inSyncBranchesCount(inSyncBranches)
                .pendingBranchesCount(pendingBranches)
                .divergedBranchesCount(divergedBranches)
                .prsTotal(prsTotal)
                .prsSynced(prsSynced)
                .lfsTotal(lfsTotal)
                .lfsSynced(lfsSynced)
                .lfsPending(lfsPending)
                .tagsSourceCount(tagsSource)
                .tagsTargetCount(tagsTarget)
                .build();
    }
}
