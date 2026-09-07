package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persists pair-level mirror statistics from completed git phases so the repo detail
 * page can show accurate counts on refresh without re-scanning LFS or waiting for full job SUCCESS.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PairMirrorSnapshotService {

    private final RepoMappingRepository repoMappingRepository;
    private final GitComparisonService gitComparisonService;
    private final PairDiffSnapshotService pairDiffSnapshotService;

    public void recordGitMirrorProgress(Long mappingId, Long jobId, GitSyncEngine.SyncResult result) {
        if (mappingId == null || result == null || !result.success) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setLastMirrorJobId(jobId);
                mapping.setLastMirrorStatsAt(Instant.now());
                mapping.setLastMirrorBranchesCount(result.branchesCount);
                mapping.setLastMirrorTagsCount(result.tagsCount);
                if (result.lfsObjectsCount > 0) {
                    mapping.setLastMirrorLfsObjects(result.lfsObjectsCount);
                } else if (result.lfsSyncedCount > 0) {
                    mapping.setLastMirrorLfsObjects(result.lfsSyncedCount);
                }
                mapping.setLastMirrorBytesTransferred(result.bytesTransferred);
                repoMappingRepository.save(mapping);
                pairDiffSnapshotService.updateFromGitResult(mappingId, result);
                gitComparisonService.invalidateQuickDiffCache(mappingId);
                log.debug("Updated mirror snapshot for mapping #{} from job #{} ({} branches, {} tags, {} LFS)",
                        mappingId, jobId, result.branchesCount, result.tagsCount, result.lfsObjectsCount);
            });
        } catch (Exception e) {
            log.debug("Could not persist mirror snapshot for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public static int lfsDiscoveredCount(RepoMapping mapping) {
        if (mapping == null) {
            return 0;
        }
        if (mapping.getLastMirrorLfsObjects() != null && mapping.getLastMirrorLfsObjects() > 0) {
            return mapping.getLastMirrorLfsObjects();
        }
        return SyncCheckpointService.parseLfsObjects(mapping.getDiscoveredLfsOids()).size();
    }

    public static int lfsSyncedCount(RepoMapping mapping) {
        if (mapping == null) {
            return 0;
        }
        int discovered = lfsDiscoveredCount(mapping);
        int completed = SyncCheckpointService.parseOidLines(mapping.getCompletedLfsOids()).size();
        if (completed > 0) {
            return Math.min(discovered > 0 ? discovered : completed, completed);
        }
        if (mapping.getLastMirrorLfsObjects() != null && mapping.getLastMirrorLfsObjects() > 0) {
            return mapping.getLastMirrorLfsObjects();
        }
        return 0;
    }
}
