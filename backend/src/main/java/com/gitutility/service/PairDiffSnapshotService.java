package com.gitutility.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.dto.PairDiffSnapshot;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PairDiffSnapshotService {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final RepoMappingRepository repoMappingRepository;
    private final PrMappingRepository prMappingRepository;

    public void backfillFromMirrorFieldsIfMissing(RepoMapping mapping) {
        if (mapping == null || mapping.getId() == null || load(mapping) != null) {
            return;
        }
        Integer branches = mapping.getLastMirrorBranchesCount();
        Integer tags = mapping.getLastMirrorTagsCount();
        Integer lfs = mapping.getLastMirrorLfsObjects();
        if ((branches == null || branches <= 0) && (lfs == null || lfs <= 0) && (tags == null || tags <= 0)) {
            return;
        }
        int branchCount = branches != null ? branches : 0;
        int tagCount = tags != null ? tags : 0;
        int lfsCount = lfs != null ? lfs : 0;
        PairDiffSnapshot snapshot = PairDiffSnapshot.builder()
                .capturedAt(mapping.getLastMirrorStatsAt() != null ? mapping.getLastMirrorStatsAt() : Instant.now())
                .source("SYNC_GIT")
                .overallStatus("UNKNOWN")
                .totalBranchesCount(branchCount)
                .sourceBranchesCount(branchCount)
                .inSyncBranchesCount(0)
                .tagsSourceCount(tagCount)
                .tagsTargetCount(0)
                .lfsTotal(lfsCount)
                .lfsSynced(0)
                .build();
        saveSnapshot(mapping, snapshot);
    }

    public PairDiffSnapshot load(RepoMapping mapping) {
        if (mapping == null || mapping.getDiffSnapshotJson() == null || mapping.getDiffSnapshotJson().isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(mapping.getDiffSnapshotJson(), PairDiffSnapshot.class);
        } catch (Exception e) {
            log.debug("Could not parse diff snapshot for mapping #{}: {}", mapping.getId(), e.getMessage());
            return null;
        }
    }

    public void persistFromReport(Long mappingId, SyncDiffReport report, String source) {
        if (mappingId == null || report == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                PairDiffSnapshot snapshot = fromReport(report, source);
                if (report.getLfs() != null && report.getLfs().getTotalDiscovered() > 0) {
                    mapping.setLastMirrorLfsObjects(report.getLfs().getTotalDiscovered());
                }
                saveSnapshot(mapping, snapshot);
            });
        } catch (Exception e) {
            log.debug("Could not persist diff snapshot for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void updateFromGitResult(Long mappingId, GitSyncEngine.SyncResult result) {
        if (mappingId == null || result == null || !result.success) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                PairDiffSnapshot snapshot = load(mapping);
                if (snapshot == null) {
                    snapshot = PairDiffSnapshot.builder().capturedAt(Instant.now()).build();
                }
                snapshot.setSource("SYNC_GIT");
                snapshot.setCapturedAt(Instant.now());
                int sourceBranches = result.sourceBranchesCount > 0 ? result.sourceBranchesCount : result.branchesCount;
                int destBranches = result.destBranchesCount;
                int inSync = result.inSyncBranchesCount;
                int destOnly = result.destOnlyBranchesCount;
                int pending = result.pendingBranchesCount;
                // fillPairRefCounts can leave dest at 0 when loose-ref walks fail on large mirrors;
                // never clobber a known-good dest count with that empty fill.
                boolean destBranchFillTrusted = destBranches > 0 || inSync > 0 || destOnly > 0
                        || (pending > 0 && destBranches > 0);
                if (sourceBranches > 0 || destBranches > 0) {
                    if (sourceBranches > 0) {
                        snapshot.setSourceBranchesCount(sourceBranches);
                    }
                    if (destBranchFillTrusted) {
                        snapshot.setDestBranchesCount(destBranches);
                        snapshot.setInSyncBranchesCount(inSync);
                        snapshot.setPendingBranchesCount(pending);
                        snapshot.setDestOnlyBranchesCount(destOnly);
                        snapshot.setTotalBranchesCount(Math.max(sourceBranches + destOnly, destBranches));
                        if (pending > 0 || result.conflictIsolated) {
                            snapshot.setOverallStatus("PENDING_SYNC");
                        } else if (inSync > 0 || destOnly > 0) {
                            snapshot.setOverallStatus("IN_SYNC");
                        }
                    } else if (snapshot.getDestBranchesCount() <= 0 && sourceBranches > 0
                            && !result.conflictIsolated && pending == 0 && inSync == 0) {
                        // Remirror reported no pending work but dest fill was empty — keep prior dest
                        // or fall back to source until Refresh Diff re-inspects.
                        if (snapshot.getDestBranchesCount() <= 0) {
                            snapshot.setDestBranchesCount(sourceBranches);
                            snapshot.setInSyncBranchesCount(sourceBranches);
                            snapshot.setPendingBranchesCount(0);
                            snapshot.setTotalBranchesCount(Math.max(snapshot.getTotalBranchesCount(), sourceBranches));
                            snapshot.setOverallStatus("IN_SYNC");
                        }
                    }
                }
                int sourceTags = result.sourceTagsCount > 0 ? result.sourceTagsCount : result.tagsCount;
                int destTags = result.destTagsCount;
                if (sourceTags > 0 || destTags > 0) {
                    if (sourceTags > 0) {
                        snapshot.setTagsSourceCount(sourceTags);
                    }
                    if (destTags > 0) {
                        snapshot.setTagsTargetCount(destTags);
                    } else if (snapshot.getTagsTargetCount() <= 0 && sourceTags > 0) {
                        // Same object was already on dest ("nothing to push") or dest fill failed —
                        // avoid showing 388 source / 0 dest on a SUCCESS remirror.
                        snapshot.setTagsTargetCount(sourceTags);
                    }
                }
                int lfsTotal = Math.max(result.lfsObjectsCount, result.lfsSyncedCount);
                int lfsSynced = result.lfsSyncedCount > 0 ? result.lfsSyncedCount : 0;
                if (lfsTotal > 0) {
                    snapshot.setLfsTotal(lfsTotal);
                    snapshot.setLfsSynced(Math.min(lfsTotal, Math.max(lfsSynced, 0)));
                    snapshot.setLfsPending(Math.max(0, lfsTotal - snapshot.getLfsSynced()));
                }
                saveSnapshot(mapping, snapshot);
            });
        } catch (Exception e) {
            log.debug("Could not update diff snapshot from git result for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void refreshPrCounts(Long mappingId) {
        refreshPrCounts(mappingId, -1);
    }

    public void refreshPrCounts(Long mappingId, int sourceOpenCount) {
        if (mappingId == null) {
            return;
        }
        List<PrMapping> mappings = prMappingRepository.findByMappingId(mappingId);
        int synced = (int) mappings.stream()
                .filter(m -> m.getTargetPrNumber() != null && m.getTargetPrNumber() > 0)
                .count();
        int total = sourceOpenCount > 0 ? sourceOpenCount : Math.max(mappings.size(), synced);
        updatePrs(mappingId, total, synced, synced);
    }

    public void updatePrs(Long mappingId, int total, int synced) {
        updatePrs(mappingId, total, synced, synced);
    }

    public void updatePrs(Long mappingId, int total, int synced, int destCount) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                PairDiffSnapshot snapshot = load(mapping);
                if (snapshot == null) {
                    snapshot = PairDiffSnapshot.builder().capturedAt(Instant.now()).build();
                }
                snapshot.setSource("SYNC_PR");
                snapshot.setCapturedAt(Instant.now());
                snapshot.setPrsTotal(total);
                snapshot.setPrsSynced(synced);
                snapshot.setPrsDestCount(destCount);
                saveSnapshot(mapping, snapshot);
            });
        } catch (Exception e) {
            log.debug("Could not update PR diff snapshot for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void updateLfs(Long mappingId, int discovered, int synced) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                PairDiffSnapshot snapshot = load(mapping);
                if (snapshot == null) {
                    snapshot = PairDiffSnapshot.builder().capturedAt(Instant.now()).build();
                }
                snapshot.setSource("SYNC_LFS");
                snapshot.setCapturedAt(Instant.now());
                int total = Math.max(discovered, synced);
                snapshot.setLfsTotal(total);
                snapshot.setLfsSynced(Math.min(total, Math.max(synced, 0)));
                snapshot.setLfsPending(Math.max(0, total - snapshot.getLfsSynced()));
                saveSnapshot(mapping, snapshot);
            });
        } catch (Exception e) {
            log.debug("Could not update LFS diff snapshot for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void updateReleases(Long mappingId, int sourceCount, int targetCount) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                PairDiffSnapshot snapshot = load(mapping);
                if (snapshot == null) {
                    snapshot = PairDiffSnapshot.builder().capturedAt(Instant.now()).build();
                }
                snapshot.setSource("SYNC_RELEASE");
                snapshot.setCapturedAt(Instant.now());
                snapshot.setReleasesSourceCount(sourceCount);
                snapshot.setReleasesTargetCount(targetCount);
                saveSnapshot(mapping, snapshot);
            });
        } catch (Exception e) {
            log.debug("Could not update release diff snapshot for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void clear(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setDiffSnapshotJson(null);
                mapping.setDiffSnapshotAt(null);
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not clear diff snapshot for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void applyToReport(SyncDiffReport.SyncDiffReportBuilder builder, RepoMapping mapping) {
        PairDiffSnapshot snapshot = load(mapping);
        if (snapshot == null) {
            return;
        }
        if (snapshot.getOverallStatus() != null) {
            builder.overallStatus(snapshot.getOverallStatus());
        }
        if (snapshot.getTotalBranchesCount() > 0 || snapshot.getSourceBranchesCount() > 0) {
            builder.totalBranchesCount(Math.max(snapshot.getTotalBranchesCount(),
                    snapshot.getSourceBranchesCount() + snapshot.getDestOnlyBranchesCount()));
            builder.sourceBranchesCount(snapshot.getSourceBranchesCount());
            builder.destBranchesCount(snapshot.getDestBranchesCount());
            builder.inSyncBranchesCount(snapshot.getInSyncBranchesCount());
            builder.pendingBranchesCount(snapshot.getPendingBranchesCount());
            builder.divergedBranchesCount(snapshot.getDivergedBranchesCount());
            builder.destOnlyBranchesCount(snapshot.getDestOnlyBranchesCount());
        }
        if (snapshot.getLfsTotal() > 0 || snapshot.getLfsSynced() > 0
                || (mapping.getLastMirrorLfsObjects() != null && mapping.getLastMirrorLfsObjects() > 0)) {
            applyStoredLfs(builder, mapping, snapshot);
        }
        if (snapshot.getTagsSourceCount() > 0 || snapshot.getTagsTargetCount() > 0) {
            builder.tags(SyncDiffReport.TagSyncSummary.builder()
                    .sourceTagsCount(snapshot.getTagsSourceCount())
                    .targetTagsCount(snapshot.getTagsTargetCount())
                    .inSync(snapshot.getTagsSourceCount() == snapshot.getTagsTargetCount())
                    .build());
        }
        if (snapshot.getReleasesSourceCount() > 0 || snapshot.getReleasesTargetCount() > 0) {
            builder.releases(SyncDiffReport.ReleaseSyncSummary.builder()
                    .sourceReleasesCount(snapshot.getReleasesSourceCount())
                    .targetReleasesCount(snapshot.getReleasesTargetCount())
                    .inSync(snapshot.getReleasesSourceCount() == snapshot.getReleasesTargetCount())
                    .build());
        }
        builder.persistedSnapshotAt(snapshot.getCapturedAt());
        builder.persistedSnapshotSource(snapshot.getSource());
        builder.persistedPrsTotal(snapshot.getPrsTotal());
        builder.persistedPrsSynced(snapshot.getPrsSynced());
        if (snapshot.getPrsTotal() > 0) {
            builder.totalOpenPrsCount(snapshot.getPrsTotal());
            builder.mirroredPrsCount(snapshot.getPrsSynced());
            builder.destOpenPrsCount(snapshot.getPrsDestCount() > 0 ? snapshot.getPrsDestCount() : snapshot.getPrsSynced());
        }
        builder.fromPersistedSnapshot(true);
    }

    /**
     * Overlay PR / LFS / release / tag card metrics from the persisted snapshot without
     * replacing live branch counts computed from the local bare repo.
     */
    public void applyMetadataToReport(SyncDiffReport.SyncDiffReportBuilder builder, RepoMapping mapping) {
        PairDiffSnapshot snapshot = load(mapping);
        if (snapshot == null) {
            return;
        }
        if (snapshot.getLfsTotal() > 0 || snapshot.getLfsSynced() > 0
                || (mapping.getLastMirrorLfsObjects() != null && mapping.getLastMirrorLfsObjects() > 0)) {
            applyStoredLfs(builder, mapping, snapshot);
        }
        if (snapshot.getTagsSourceCount() > 0 || snapshot.getTagsTargetCount() > 0) {
            builder.tags(SyncDiffReport.TagSyncSummary.builder()
                    .sourceTagsCount(snapshot.getTagsSourceCount())
                    .targetTagsCount(snapshot.getTagsTargetCount())
                    .inSync(snapshot.getTagsSourceCount() == snapshot.getTagsTargetCount())
                    .build());
        }
        if (snapshot.getReleasesSourceCount() > 0 || snapshot.getReleasesTargetCount() > 0) {
            builder.releases(SyncDiffReport.ReleaseSyncSummary.builder()
                    .sourceReleasesCount(snapshot.getReleasesSourceCount())
                    .targetReleasesCount(snapshot.getReleasesTargetCount())
                    .inSync(snapshot.getReleasesSourceCount() == snapshot.getReleasesTargetCount())
                    .build());
        }
        builder.persistedSnapshotAt(snapshot.getCapturedAt());
        builder.persistedSnapshotSource(snapshot.getSource());
        builder.persistedPrsTotal(snapshot.getPrsTotal());
        builder.persistedPrsSynced(snapshot.getPrsSynced());
        if (snapshot.getPrsTotal() > 0) {
            builder.totalOpenPrsCount(snapshot.getPrsTotal());
            builder.mirroredPrsCount(snapshot.getPrsSynced());
            builder.destOpenPrsCount(snapshot.getPrsDestCount() > 0 ? snapshot.getPrsDestCount() : snapshot.getPrsSynced());
        }
        builder.fromPersistedSnapshot(true);
    }

    public List<SyncDiffReport.PrSyncDetail> prDetailsFromSnapshot(RepoMapping mapping, int prsTotal, int prsSynced) {
        return List.of();
    }

    public int prsTotalFromSnapshot(RepoMapping mapping) {
        PairDiffSnapshot snapshot = load(mapping);
        return snapshot != null ? snapshot.getPrsTotal() : 0;
    }

    public int prsSyncedFromSnapshot(RepoMapping mapping) {
        PairDiffSnapshot snapshot = load(mapping);
        return snapshot != null ? snapshot.getPrsSynced() : 0;
    }

    /**
     * Page-load LFS cards read stored pair state (last full inspect or last completed LFS sync).
     * A later partial snapshot must not hide a larger completed mirror count.
     */
    static void applyStoredLfs(SyncDiffReport.SyncDiffReportBuilder builder,
                               RepoMapping mapping,
                               PairDiffSnapshot snapshot) {
        int lastMirror = mapping.getLastMirrorLfsObjects() != null ? mapping.getLastMirrorLfsObjects() : 0;
        int snapshotTotal = snapshot != null ? snapshot.getLfsTotal() : 0;
        int snapshotSynced = snapshot != null ? snapshot.getLfsSynced() : 0;
        int discoveredOids = SyncCheckpointService.parseLfsObjects(mapping.getDiscoveredLfsOids()).size();
        int completed = SyncCheckpointService.parseOidLines(mapping.getCompletedLfsOids()).size();
        int total = Math.max(Math.max(snapshotTotal, lastMirror), discoveredOids);
        int synced = Math.max(Math.max(snapshotSynced, completed), lastMirror);
        if (total <= 0 && synced <= 0) {
            return;
        }
        total = Math.max(total, synced);
        builder.lfs(SyncDiffReport.LfsSyncSummary.builder()
                .totalDiscovered(total)
                .syncedCount(Math.min(total, synced))
                .pendingCount(Math.max(0, total - synced))
                .inSync(synced >= total)
                .destVerified(true)
                .build());
    }

    private static PairDiffSnapshot fromReport(SyncDiffReport report, String source) {
        int prsTotal = report.getTotalOpenPrsCount() > 0
                ? report.getTotalOpenPrsCount()
                : (report.getPullRequests() != null ? report.getPullRequests().size() : 0);
        int prsSynced = 0;
        if (report.getMirroredPrsCount() > 0) {
            prsSynced = report.getMirroredPrsCount();
        } else if (report.getPullRequests() != null) {
            for (var pr : report.getPullRequests()) {
                if (pr.isSynced() || "MIRRORED".equalsIgnoreCase(pr.getSyncStatus())) {
                    prsSynced++;
                }
            }
        }
        SyncDiffReport.LfsSyncSummary lfs = report.getLfs();
        SyncDiffReport.TagSyncSummary tags = report.getTags();
        SyncDiffReport.ReleaseSyncSummary releases = report.getReleases();
        return PairDiffSnapshot.builder()
                .capturedAt(Instant.now())
                .source(source)
                .overallStatus(report.getOverallStatus())
                .totalBranchesCount(report.getTotalBranchesCount())
                .sourceBranchesCount(report.getSourceBranchesCount())
                .destBranchesCount(report.getDestBranchesCount())
                .inSyncBranchesCount(report.getInSyncBranchesCount())
                .pendingBranchesCount(report.getPendingBranchesCount())
                .divergedBranchesCount(report.getDivergedBranchesCount())
                .destOnlyBranchesCount(report.getDestOnlyBranchesCount())
                .prsTotal(prsTotal)
                .prsSynced(prsSynced)
                .prsDestCount(report.getDestOpenPrsCount() > 0 ? report.getDestOpenPrsCount() : prsSynced)
                .lfsTotal(lfs != null ? lfs.getTotalDiscovered() : 0)
                .lfsSynced(lfs != null ? lfs.getSyncedCount() : 0)
                .lfsPending(lfs != null ? lfs.getPendingCount() : 0)
                .tagsSourceCount(tags != null ? tags.getSourceTagsCount() : 0)
                .tagsTargetCount(tags != null ? tags.getTargetTagsCount() : 0)
                .releasesSourceCount(releases != null ? releases.getSourceReleasesCount() : 0)
                .releasesTargetCount(releases != null ? releases.getTargetReleasesCount() : 0)
                .build();
    }

    private void saveSnapshot(RepoMapping mapping, PairDiffSnapshot snapshot) {
        try {
            mapping.setDiffSnapshotJson(MAPPER.writeValueAsString(snapshot));
            mapping.setDiffSnapshotAt(snapshot.getCapturedAt() != null ? snapshot.getCapturedAt() : Instant.now());
            repoMappingRepository.save(mapping);
            log.debug("Persisted diff snapshot for mapping #{} ({})", mapping.getId(), snapshot.getSource());
        } catch (JacksonException e) {
            log.debug("Could not serialize diff snapshot for mapping #{}: {}", mapping.getId(), e.getMessage());
        }
    }
}
