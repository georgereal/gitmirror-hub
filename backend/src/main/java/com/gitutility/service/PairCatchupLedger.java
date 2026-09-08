package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.SyncCheckpointStage;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pair-durable catch-up watermarks that survive job SUCCESS. Wiped only by Start fresh
 * ({@link SyncCheckpointService#resetPairProgress(Long)}). Job-local push ledgers stay separate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PairCatchupLedger {

    static final Duration FORK_MISS_TTL = Duration.ofHours(6);
    static final Duration PR_LIST_OVERLAP = Duration.ofMinutes(2);

    private final RepoMappingRepository repoMappingRepository;

    public static String fingerprintTips(Map<String, String> tips) {
        if (tips == null || tips.isEmpty()) {
            return null;
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : tips.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            sorted.put(entry.getKey(), entry.getValue().toLowerCase());
        }
        if (sorted.isEmpty()) {
            return null;
        }
        StringBuilder blob = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            blob.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(blob.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(blob.toString().hashCode());
        }
    }

    public void recordGitSuccess(Long mappingId, GitSyncEngine.SyncResult result) {
        if (mappingId == null || result == null || !result.success) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                if (result.sourceTipFingerprint != null && !result.sourceTipFingerprint.isBlank()) {
                    mapping.setLastSourceTipFingerprint(result.sourceTipFingerprint);
                }
                if (result.destTipFingerprint != null && !result.destTipFingerprint.isBlank()) {
                    mapping.setLastDestTipFingerprint(result.destTipFingerprint);
                }
                if (result.lfsScannedTipOids != null && !result.lfsScannedTipOids.isEmpty()) {
                    mapping.setLfsScannedTipOids(SyncCheckpointService.serializeOidLines(result.lfsScannedTipOids));
                }
                mapping.setSyncCheckpointStage(SyncCheckpointStage.GIT_SYNC_DONE.name());
                mapping.setCompletedPushRefs(null);
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist git catch-up watermarks for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void recordPrListCompleted(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setLastPrListCompletedAt(Instant.now());
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist PR list watermark for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void recordReleaseSyncCompleted(Long mappingId) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setLastReleaseSyncAt(Instant.now());
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist release watermark for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void recordInspectSuccess(Long mappingId, String sourceFingerprint, String destFingerprint,
                                     List<GitLfsSyncService.LfsObject> verifiedLfs) {
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                if (sourceFingerprint != null && !sourceFingerprint.isBlank()) {
                    mapping.setLastSourceTipFingerprint(sourceFingerprint);
                }
                if (destFingerprint != null && !destFingerprint.isBlank()) {
                    mapping.setLastDestTipFingerprint(destFingerprint);
                }
                if (verifiedLfs != null && !verifiedLfs.isEmpty()) {
                    Set<String> completed = SyncCheckpointService.parseOidLines(mapping.getCompletedLfsOids());
                    for (GitLfsSyncService.LfsObject obj : verifiedLfs) {
                        if (obj != null && obj.oid() != null) {
                            completed.add(obj.oid());
                        }
                    }
                    mapping.setCompletedLfsOids(SyncCheckpointService.serializeOidLines(completed));
                }
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist inspect watermarks for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public Instant lastPrListCompletedAt(RepoMapping mapping) {
        return mapping != null ? mapping.getLastPrListCompletedAt() : null;
    }

    public Instant lastReleaseSyncAt(RepoMapping mapping) {
        return mapping != null ? mapping.getLastReleaseSyncAt() : null;
    }

    public boolean shouldSkipForkFetch(RepoMapping mapping, long sourcePrNumber) {
        Instant missed = loadForkMisses(mapping).get(sourcePrNumber);
        if (missed == null) {
            return false;
        }
        return missed.plus(FORK_MISS_TTL).isAfter(Instant.now());
    }

    public Map<Long, Instant> loadForkMisses(RepoMapping mapping) {
        Map<Long, Instant> misses = new LinkedHashMap<>();
        if (mapping == null || mapping.getForkPrMissJson() == null || mapping.getForkPrMissJson().isBlank()) {
            return misses;
        }
        Instant cutoff = Instant.now().minus(FORK_MISS_TTL);
        for (String line : mapping.getForkPrMissJson().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            try {
                long number = Long.parseLong(trimmed.substring(0, tab).trim());
                Instant at = Instant.parse(trimmed.substring(tab + 1).trim());
                if (at.isAfter(cutoff)) {
                    misses.put(number, at);
                }
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        return misses;
    }

    public void recordForkMisses(Long mappingId, Set<Long> sourcePrNumbers) {
        if (mappingId == null || sourcePrNumbers == null || sourcePrNumbers.isEmpty()) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                Map<Long, Instant> misses = loadForkMisses(mapping);
                Instant now = Instant.now();
                for (Long number : sourcePrNumbers) {
                    if (number != null && number > 0) {
                        misses.put(number, now);
                    }
                }
                mapping.setForkPrMissJson(serializeForkMisses(misses));
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist fork PR misses for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    public void clearForkMisses(Long mappingId, Set<Long> sourcePrNumbers) {
        if (mappingId == null || sourcePrNumbers == null || sourcePrNumbers.isEmpty()) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                Map<Long, Instant> misses = loadForkMisses(mapping);
                boolean changed = false;
                for (Long number : sourcePrNumbers) {
                    if (misses.remove(number) != null) {
                        changed = true;
                    }
                }
                if (changed) {
                    mapping.setForkPrMissJson(serializeForkMisses(misses));
                    repoMappingRepository.save(mapping);
                }
            });
        } catch (Exception e) {
            log.debug("Could not clear fork PR misses for mapping #{}: {}", mappingId, e.getMessage());
        }
    }

    static String serializeForkMisses(Map<Long, Instant> misses) {
        if (misses == null || misses.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Instant> entry : misses.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            sb.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public static Instant deltaCutoff(Instant lastCompletedAt) {
        if (lastCompletedAt == null) {
            return null;
        }
        return lastCompletedAt.minus(PR_LIST_OVERLAP);
    }

    public static boolean allItemsOlderThan(List<com.gitutility.model.dto.SyncDiffReport.PrSyncDetail> items,
                                            Instant cutoff) {
        if (cutoff == null || items == null || items.isEmpty()) {
            return false;
        }
        for (com.gitutility.model.dto.SyncDiffReport.PrSyncDetail pr : items) {
            if (pr == null || pr.getUpdatedAt() == null) {
                return false;
            }
            if (!pr.getUpdatedAt().isBefore(cutoff)) {
                return false;
            }
        }
        return true;
    }

    public static Set<String> tipOidHexes(Set<org.eclipse.jgit.lib.ObjectId> tips) {
        Set<String> hexes = new LinkedHashSet<>();
        if (tips == null) {
            return hexes;
        }
        for (org.eclipse.jgit.lib.ObjectId id : tips) {
            if (id != null) {
                hexes.add(org.eclipse.jgit.lib.ObjectId.toString(id));
            }
        }
        return hexes;
    }

    public static List<Long> parseClosedSourceNumbers(List<com.gitutility.model.dto.SyncDiffReport.PrSyncDetail> items) {
        List<Long> numbers = new ArrayList<>();
        if (items == null) {
            return numbers;
        }
        for (com.gitutility.model.dto.SyncDiffReport.PrSyncDetail pr : items) {
            if (pr != null && pr.getSourcePrNumber() != null) {
                numbers.add(pr.getSourcePrNumber());
            }
        }
        return numbers;
    }
}
