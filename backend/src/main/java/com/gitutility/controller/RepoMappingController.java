package com.gitutility.controller;

import com.gitutility.model.dto.BulkMirrorRequest;
import com.gitutility.model.dto.BulkMirrorResponse;
import com.gitutility.model.dto.DiffInspectOptions;
import com.gitutility.model.dto.RepoMappingResponse;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mappings")
@RequiredArgsConstructor
@Slf4j
public class RepoMappingController {

    private final RepoMappingService mappingService;
    private final BulkMirrorService bulkMirrorService;
    private final GitComparisonService gitComparisonService;
    private final PullRequestSyncService pullRequestSyncService;
    private final GitLfsSyncService gitLfsSyncService;
    private final ReleaseAndStatusSyncService releaseAndStatusSyncService;
    private final StorageTieringService storageTieringService;
    private final SyncConflictService syncConflictService;
    private final PairDiffSnapshotService pairDiffSnapshotService;
    private final ReplicaRulesetService replicaRulesetService;
    private final WriteAuthorityService writeAuthorityService;
    private final MetadataSyncSettingsService metadataSyncSettingsService;
    private final FailoverService failoverService;

    @GetMapping
    public ResponseEntity<List<RepoMappingResponse>> getAllMappings() {
        List<RepoMapping> entities = mappingService.getAllMappings();
        Map<String, String> notes = writeAuthorityService.notesFor(entities);
        List<RepoMappingResponse> list = entities.stream()
                .map(entity -> present(entity, notes))
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepoMappingResponse> getMappingById(@PathVariable String id) {
        return mappingService.getMappingById(id)
                .map(this::present)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RepoMappingResponse> createMapping(@RequestBody RepoMapping mapping) {
        RepoMapping created = mappingService.createMapping(mapping);
        return ResponseEntity.status(HttpStatus.CREATED).body(present(created));
    }

    /** Bulk migration submission (Bulk migration tab): one pair + bootstrap job per accepted row. */
    @PostMapping("/bulk")
    public ResponseEntity<BulkMirrorResponse> createBulkMappings(@RequestBody BulkMirrorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bulkMirrorService.submit(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RepoMappingResponse> updateMapping(@PathVariable String id, @RequestBody RepoMapping mapping) {
        RepoMapping updated = mappingService.updateMapping(id, mapping);
        return ResponseEntity.ok(present(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMapping(@PathVariable String id) {
        mappingService.deleteMapping(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<SyncJob> triggerManualSync(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String branch = body != null && body.get("branch") != null ? String.valueOf(body.get("branch")) : "main";
        String direction = body != null && body.get("direction") != null ? String.valueOf(body.get("direction")) : "A_TO_B";
        boolean overwriteFromSource = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("overwriteFromSource", false)));
        boolean startFresh = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("startFresh", false)));
        SyncJob job = mappingService.triggerManualSync(id, branch, direction, overwriteFromSource, startFresh);
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/replica-ruleset")
    public ResponseEntity<?> applyReplicaRuleset(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String action = body != null && body.get("action") != null ? String.valueOf(body.get("action")) : "lock";
        String primarySide = body != null && body.get("primarySide") != null
                ? String.valueOf(body.get("primarySide")) : null;
        try {
            RepoMapping updated = replicaRulesetService.apply(id, action, primarySide);
            return ResponseEntity.ok(present(updated));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/{id}/peer-status")
    public ResponseEntity<com.gitutility.model.dto.PeerStatusResponse> peerStatus(@PathVariable String id) {
        return ResponseEntity.ok(failoverService.status(id));
    }

    @PostMapping("/{id}/peer-heartbeat")
    public ResponseEntity<com.gitutility.model.dto.PeerStatusResponse> peerHeartbeat(@PathVariable String id) {
        return ResponseEntity.ok(failoverService.probe(id));
    }

    @PostMapping("/{id}/activate-dr")
    public ResponseEntity<com.gitutility.model.dto.PeerStatusResponse> activateDr(@PathVariable String id) {
        return ResponseEntity.ok(failoverService.activateDr(id));
    }

    @PostMapping("/{id}/fail-back")
    public ResponseEntity<com.gitutility.model.dto.PeerStatusResponse> failBack(@PathVariable String id) {
        return ResponseEntity.ok(failoverService.failBack(id));
    }

    @GetMapping("/{id}/sync-diff")
    public ResponseEntity<SyncDiffReport> getSyncDiff(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "false") boolean metadata,
            @RequestParam(required = false) Integer maxBranches,
            @RequestParam(required = false) Integer branchOffset,
            @RequestParam(required = false) String branchSearch,
            @RequestParam(required = false) String branchStatus) {
        return ResponseEntity.ok(gitComparisonService.computeSyncDiff(
                id, DiffInspectOptions.fromRequest(
                        refresh, metadata, maxBranches, branchOffset, branchSearch, branchStatus)));
    }

    @PostMapping("/{id}/sync-prs")
    public ResponseEntity<Map<String, Object>> syncPullRequests(@PathVariable String id) {
        metadataSyncSettingsService.requirePullRequestsEnabled();
        RepoMapping mapping = mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        int count = pullRequestSyncService.syncOpenPullRequests(mapping.getId(), mapping.getRepoAUrl(), mapping.getRepoBUrl());
        return ResponseEntity.ok(Map.of(
                "syncedCount", count,
                "message", "Synchronized " + count + " open pull request(s)"
        ));
    }

    /**
     * Promote a DR-cached fork PR tip into a dest {@code fork-pr-*} branch + GitHub PR.
     */
    @PostMapping("/{id}/prs/{sourcePrNumber}/materialize-fork")
    public ResponseEntity<Map<String, Object>> materializeForkPr(
            @PathVariable String id,
            @PathVariable long sourcePrNumber) {
        Long targetPr = pullRequestSyncService.materializeForkPrForDr(id, sourcePrNumber);
        return ResponseEntity.ok(Map.of(
                "sourcePrNumber", sourcePrNumber,
                "targetPrNumber", targetPr,
                "message", "Materialized fork PR #" + sourcePrNumber + " as dest PR #" + targetPr
        ));
    }

    @PostMapping("/{id}/sync-lfs")
    public ResponseEntity<Map<String, Object>> syncLfsObjects(@PathVariable String id) {
        metadataSyncSettingsService.requireLfsEnabled();
        RepoMapping mapping = mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        File repoDir = storageTieringService != null
                ? storageTieringService.resolveRepoDirectory(mapping.getId(), mapping.getStorageTier())
                : new File("/tmp/git-utility-mirrors/pair-" + mapping.getId() + ".git");

        if (!repoDir.exists()) {
            return ResponseEntity.ok(Map.of(
                    "syncedCount", 0,
                    "bytesTransferred", 0,
                    "message", "No local repository pack on disk. Run a Git sync first to fetch objects."
            ));
        }

        try (Git git = Git.open(repoDir)) {
            var pointers = gitLfsSyncService.discoverLfsPointers(git.getRepository());
            var stats = gitLfsSyncService.syncLfsObjects(
                    mapping.getRepoAUrl(),
                    mapping.getRepoBUrl(),
                    pointers,
                    null,
                    null,
                    null,
                    mapping.getTokenA(),
                    mapping.getTokenB(),
                    null);
            int discovered = pointers != null ? pointers.size() : 0;
            pairDiffSnapshotService.updateLfs(id, discovered, stats.count());
            gitComparisonService.invalidateQuickDiffCache(id);
            return ResponseEntity.ok(Map.of(
                    "syncedCount", stats.count(),
                    "bytesTransferred", stats.bytes(),
                    "message", "Synchronized " + stats.count() + " Git LFS blob(s)"
            ));
        } catch (Exception e) {
            log.warn("Standalone LFS sync error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/sync-releases")
    public ResponseEntity<Map<String, Object>> syncReleases(@PathVariable String id) {
        metadataSyncSettingsService.requireReleasesEnabled();
        mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        String jobId = releaseAndStatusSyncService.launchReleaseSyncJob(id);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "syncedCount", 0,
                "message", "Release mirror started (job #" + jobId + ") — track it in Queue Manager and the audit log"
        ));
    }

    /** Standalone CI check backfill (check runs + commit statuses onto mirrored tips). */
    @PostMapping("/{id}/sync-ci-checks")
    public ResponseEntity<Map<String, Object>> syncCiChecks(@PathVariable String id) {
        metadataSyncSettingsService.requireCiChecksEnabled();
        mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        String jobId = releaseAndStatusSyncService.launchCiCheckSyncJob(id);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "syncedCount", 0,
                "message", "CI check backfill started (job #" + jobId + ") — track it in Queue Manager and the audit log"
        ));
    }

    @GetMapping("/{id}/conflicts")
    public ResponseEntity<List<com.gitutility.model.entity.SyncConflict>> listConflicts(@PathVariable String id) {
        mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        return ResponseEntity.ok(syncConflictService.listForMapping(id));
    }

    @PostMapping("/{id}/conflicts/{conflictId}/resolve")
    public ResponseEntity<?> resolveConflict(@PathVariable String id, @PathVariable String conflictId) {
        return syncConflictService.resolve(id, conflictId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/conflicts/{conflictId}/open-pr")
    public ResponseEntity<?> openConflictPr(@PathVariable String id, @PathVariable String conflictId) {
        RepoMapping mapping = mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        return syncConflictService.retryOpenPr(id, conflictId, mapping, destUrlForConflict(mapping, conflictId))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private String destUrlForConflict(RepoMapping mapping, String conflictId) {
        return syncConflictService.listForMapping(mapping.getId()).stream()
                .filter(c -> conflictId.equals(c.getId()))
                .map(c -> {
                    if (c.getDestRepo() != null && c.getDestRepo().contains("://")) {
                        return c.getDestRepo();
                    }
                    return mapping.getRepoBUrl();
                })
                .findFirst()
                .orElse(mapping.getRepoBUrl());
    }

    private RepoMappingResponse present(RepoMapping entity) {
        return present(entity, writeAuthorityService.notesFor(List.of(entity)));
    }

    private RepoMappingResponse present(RepoMapping entity, Map<String, String> notes) {
        RepoMappingResponse response = RepoMappingResponse.fromEntity(entity);
        if (notes != null) {
            response.setWriteAuthorityNote(notes.get(entity.getId()));
        }
        try {
            response.setPeerStatus(failoverService.status(entity.getId()));
        } catch (Exception ignored) {
            // snapshot is optional on list
        }
        return response;
    }
}
