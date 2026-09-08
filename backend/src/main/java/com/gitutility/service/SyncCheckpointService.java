package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.SyncCheckpointStage;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncCheckpointService {

    private final RepoMappingRepository repoMappingRepository;

    public SyncCheckpointStage getStage(RepoMapping mapping) {
        if (mapping == null || mapping.getSyncCheckpointStage() == null
                || mapping.getSyncCheckpointStage().isBlank()) {
            return SyncCheckpointStage.NONE;
        }
        try {
            return SyncCheckpointStage.valueOf(mapping.getSyncCheckpointStage().trim());
        } catch (IllegalArgumentException e) {
            return SyncCheckpointStage.NONE;
        }
    }

    public boolean hasCheckpoint(RepoMapping mapping) {
        return getStage(mapping) != SyncCheckpointStage.NONE;
    }

    /**
     * Promotes a fully transferred LFS checkpoint to {@link SyncCheckpointStage#GIT_SYNC_DONE}
     * so resume skips redundant git mirror work.
     */
    public SyncCheckpointStage resolveResumeStage(RepoMapping mapping) {
        SyncCheckpointStage stage = getStage(mapping);
        if (mapping == null || stage == SyncCheckpointStage.NONE || stage == SyncCheckpointStage.GIT_SYNC_DONE) {
            return stage;
        }
        if (stage.ordinal() < SyncCheckpointStage.LFS_DISCOVERY_DONE.ordinal()) {
            return stage;
        }
        List<GitLfsSyncService.LfsObject> discovered = loadDiscoveredLfs(mapping);
        if (discovered.isEmpty()) {
            return SyncCheckpointStage.GIT_SYNC_DONE;
        }
        Set<String> completed = loadCompletedLfsOids(mapping);
        boolean allTransferred = discovered.stream().allMatch(o -> completed.contains(o.oid()));
        return allTransferred ? SyncCheckpointStage.GIT_SYNC_DONE : stage;
    }

    public void persistStage(Long mappingId, SyncCheckpointStage stage) {
        if (mappingId == null || stage == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setSyncCheckpointStage(stage == SyncCheckpointStage.NONE ? null : stage.name());
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist sync checkpoint stage: {}", e.getMessage());
        }
    }

    public void clearCheckpoint(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setSyncCheckpointStage(null);
                mapping.setCompletedLfsOids(null);
                mapping.setDiscoveredLfsOids(null);
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not clear sync checkpoint: {}", e.getMessage());
        }
    }

    /**
     * Clears in-flight push resume ledger without wiping pair-durable LFS/PR watermarks.
     */
    public void clearResumeCheckpoint(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setSyncCheckpointStage(null);
                mapping.setCompletedPushRefs(null);
                repoMappingRepository.save(mapping);
                log.info("Cleared resume checkpoint for mapping #{} ({})", mappingId, mapping.getName());
            });
        } catch (Exception e) {
            log.debug("Could not clear resume checkpoint: {}", e.getMessage());
        }
    }

    /**
     * Job finished successfully: drop in-flight push resume ledger, keep LFS/PR catch-up watermarks.
     */
    public void markCaughtUp(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setCompletedPushRefs(null);
                mapping.setSyncCheckpointStage(SyncCheckpointStage.GIT_SYNC_DONE.name());
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not mark pair caught up for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void persistLfsScannedTips(Long mappingId, Set<String> tipOids) {
        if (mappingId == null || tipOids == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setLfsScannedTipOids(serializeOidLines(tipOids));
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist LFS scanned tips: {}", e.getMessage());
        }
    }

    public Set<String> loadLfsScannedTips(RepoMapping mapping) {
        return parseOidLines(mapping != null ? mapping.getLfsScannedTipOids() : null);
    }

    public void mergeDiscoveredLfs(Long mappingId, List<GitLfsSyncService.LfsObject> additional) {
        if (mappingId == null || additional == null || additional.isEmpty()) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                Map<String, GitLfsSyncService.LfsObject> byOid = new LinkedHashMap<>();
                for (GitLfsSyncService.LfsObject obj : parseLfsObjects(mapping.getDiscoveredLfsOids())) {
                    byOid.put(obj.oid(), obj);
                }
                for (GitLfsSyncService.LfsObject obj : additional) {
                    if (obj != null && obj.oid() != null) {
                        byOid.put(obj.oid(), obj);
                    }
                }
                mapping.setDiscoveredLfsOids(serializeLfsObjects(new ArrayList<>(byOid.values())));
                mapping.setSyncCheckpointStage(SyncCheckpointStage.LFS_DISCOVERY_DONE.name());
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not merge discovered LFS OIDs: {}", e.getMessage());
        }
    }

    /**
     * Clears all pair-level resume state and mirror display stats so the next full sync starts from scratch.
     */
    public void resetPairProgress(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setSyncCheckpointStage(null);
                mapping.setCompletedLfsOids(null);
                mapping.setDiscoveredLfsOids(null);
                mapping.setLfsScannedTipOids(null);
                mapping.setCompletedPushRefs(null);
                mapping.setLastSourceTipFingerprint(null);
                mapping.setLastDestTipFingerprint(null);
                mapping.setLastPrListCompletedAt(null);
                mapping.setLastReleaseSyncAt(null);
                mapping.setForkPrMissJson(null);
                mapping.setLastMirrorJobId(null);
                mapping.setLastMirrorStatsAt(null);
                mapping.setLastMirrorBranchesCount(null);
                mapping.setLastMirrorTagsCount(null);
                mapping.setLastMirrorLfsObjects(null);
                mapping.setLastMirrorBytesTransferred(null);
                mapping.setDiffSnapshotJson(null);
                mapping.setDiffSnapshotAt(null);
                repoMappingRepository.save(mapping);
                log.info("Reset pair progress for mapping #{} ({})", mappingId, mapping.getName());
            });
        } catch (Exception e) {
            log.debug("Could not reset pair progress: {}", e.getMessage());
        }
    }

    public void persistDiscoveredLfs(Long mappingId, List<GitLfsSyncService.LfsObject> objects) {
        if (mappingId == null || objects == null || objects.isEmpty()) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setDiscoveredLfsOids(serializeLfsObjects(objects));
                mapping.setSyncCheckpointStage(SyncCheckpointStage.LFS_DISCOVERY_DONE.name());
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist discovered LFS OIDs: {}", e.getMessage());
        }
    }

    public void appendCompletedLfsOid(Long mappingId, String oid) {
        if (mappingId == null || oid == null || oid.isBlank()) {
            return;
        }
        appendCompletedLfsOids(mappingId, Set.of(oid.trim()));
    }

    public void appendCompletedLfsOids(Long mappingId, java.util.Collection<String> oids) {
        if (mappingId == null || oids == null || oids.isEmpty()) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                Set<String> existing = parseOidLines(mapping.getCompletedLfsOids());
                boolean changed = false;
                for (String oid : oids) {
                    if (oid != null && !oid.isBlank() && existing.add(oid.trim())) {
                        changed = true;
                    }
                }
                if (changed) {
                    mapping.setCompletedLfsOids(serializeOidLines(existing));
                    mapping.setSyncCheckpointStage(SyncCheckpointStage.LFS_TRANSFER_PARTIAL.name());
                    repoMappingRepository.save(mapping);
                }
            });
        } catch (Exception e) {
            log.debug("Could not append completed LFS OIDs: {}", e.getMessage());
        }
    }

    public List<GitLfsSyncService.LfsObject> loadDiscoveredLfs(RepoMapping mapping) {
        return parseLfsObjects(mapping != null ? mapping.getDiscoveredLfsOids() : null);
    }

    public Set<String> loadCompletedLfsOids(RepoMapping mapping) {
        return parseOidLines(mapping != null ? mapping.getCompletedLfsOids() : null);
    }

    static List<GitLfsSyncService.LfsObject> parseLfsObjects(String blob) {
        List<GitLfsSyncService.LfsObject> out = new ArrayList<>();
        if (blob == null || blob.isBlank()) {
            return out;
        }
        for (String line : blob.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String oid = trimmed.substring(0, tab).trim();
            String sizePart = trimmed.substring(tab + 1).trim();
            try {
                long size = Long.parseLong(sizePart);
                out.add(new GitLfsSyncService.LfsObject(oid, size));
            } catch (NumberFormatException ignored) {
                // skip malformed line
            }
        }
        return out;
    }

    static String serializeLfsObjects(List<GitLfsSyncService.LfsObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (GitLfsSyncService.LfsObject obj : objects) {
            if (obj == null || obj.oid() == null) {
                continue;
            }
            sb.append(obj.oid()).append('\t').append(obj.size()).append('\n');
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public static Set<String> parseOidLines(String blob) {
        Set<String> oids = new LinkedHashSet<>();
        if (blob == null || blob.isBlank()) {
            return oids;
        }
        for (String line : blob.split("\\R")) {
            String oid = line.trim();
            if (!oid.isEmpty()) {
                oids.add(oid);
            }
        }
        return oids;
    }

    public static String serializeOidLines(Set<String> oids) {
        if (oids == null || oids.isEmpty()) {
            return null;
        }
        return String.join("\n", oids) + "\n";
    }
}
