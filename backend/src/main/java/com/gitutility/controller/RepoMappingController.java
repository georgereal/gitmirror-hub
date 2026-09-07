package com.gitutility.controller;

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
    private final GitComparisonService gitComparisonService;
    private final PullRequestSyncService pullRequestSyncService;
    private final GitLfsSyncService gitLfsSyncService;
    private final ReleaseAndStatusSyncService releaseAndStatusSyncService;
    private final StorageTieringService storageTieringService;
    private final SyncConflictService syncConflictService;
    private final PairDiffSnapshotService pairDiffSnapshotService;

    @GetMapping
    public ResponseEntity<List<RepoMappingResponse>> getAllMappings() {
        List<RepoMappingResponse> list = mappingService.getAllMappings().stream()
                .map(RepoMappingResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepoMappingResponse> getMappingById(@PathVariable Long id) {
        return mappingService.getMappingById(id)
                .map(RepoMappingResponse::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RepoMappingResponse> createMapping(@RequestBody RepoMapping mapping) {
        RepoMapping created = mappingService.createMapping(mapping);
        return ResponseEntity.status(HttpStatus.CREATED).body(RepoMappingResponse.fromEntity(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RepoMappingResponse> updateMapping(@PathVariable Long id, @RequestBody RepoMapping mapping) {
        RepoMapping updated = mappingService.updateMapping(id, mapping);
        return ResponseEntity.ok(RepoMappingResponse.fromEntity(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMapping(@PathVariable Long id) {
        mappingService.deleteMapping(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<SyncJob> triggerManualSync(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        String branch = body != null && body.get("branch") != null ? String.valueOf(body.get("branch")) : "main";
        String direction = body != null && body.get("direction") != null ? String.valueOf(body.get("direction")) : "A_TO_B";
        boolean overwriteFromSource = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("overwriteFromSource", false)));
        boolean startFresh = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("startFresh", false)));
        SyncJob job = mappingService.triggerManualSync(id, branch, direction, overwriteFromSource, startFresh);
        return ResponseEntity.ok(job);
    }

    @GetMapping("/{id}/sync-diff")
    public ResponseEntity<SyncDiffReport> getSyncDiff(
            @PathVariable Long id,
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
    public ResponseEntity<Map<String, Object>> syncPullRequests(@PathVariable Long id) {
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
            @PathVariable Long id,
            @PathVariable long sourcePrNumber) {
        Long targetPr = pullRequestSyncService.materializeForkPrForDr(id, sourcePrNumber);
        return ResponseEntity.ok(Map.of(
                "sourcePrNumber", sourcePrNumber,
                "targetPrNumber", targetPr,
                "message", "Materialized fork PR #" + sourcePrNumber + " as dest PR #" + targetPr
        ));
    }

    @PostMapping("/{id}/sync-lfs")
    public ResponseEntity<Map<String, Object>> syncLfsObjects(@PathVariable Long id) {
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
    public ResponseEntity<Map<String, Object>> syncReleases(@PathVariable Long id) {
        RepoMapping mapping = mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        int count = releaseAndStatusSyncService.syncReleases(mapping.getId(), mapping.getRepoAUrl(), mapping.getRepoBUrl());
        return ResponseEntity.ok(Map.of(
                "syncedCount", count,
                "message", "Synchronized " + count + " release(s) with assets"
        ));
    }

    @GetMapping("/{id}/conflicts")
    public ResponseEntity<List<com.gitutility.model.entity.SyncConflict>> listConflicts(@PathVariable Long id) {
        mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        return ResponseEntity.ok(syncConflictService.listForMapping(id));
    }

    @PostMapping("/{id}/conflicts/{conflictId}/resolve")
    public ResponseEntity<?> resolveConflict(@PathVariable Long id, @PathVariable Long conflictId) {
        return syncConflictService.resolve(id, conflictId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/conflicts/{conflictId}/open-pr")
    public ResponseEntity<?> openConflictPr(@PathVariable Long id, @PathVariable Long conflictId) {
        RepoMapping mapping = mappingService.getMappingById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + id));
        return syncConflictService.retryOpenPr(id, conflictId, mapping, destUrlForConflict(mapping, conflictId))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private String destUrlForConflict(RepoMapping mapping, Long conflictId) {
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
}
