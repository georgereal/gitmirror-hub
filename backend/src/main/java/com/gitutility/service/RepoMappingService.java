package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepoMappingService {

    private final RepoMappingRepository mappingRepository;
    private final SyncJobRepository syncJobRepository;
    private final QueueProducerService queueProducerService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final SyncCheckpointService syncCheckpointService;
    private final ScmCredentialService scmCredentialService;

    public List<RepoMapping> getAllMappings() {
        return mappingRepository.findAll();
    }

    public Optional<RepoMapping> getMappingById(Long id) {
        return mappingRepository.findById(id);
    }

    public RepoMapping createMapping(RepoMapping mapping) {
        if (mapping.getName() == null || mapping.getName().isBlank()) {
            mapping.setName(deriveNameFromUrl(mapping.getRepoAUrl()));
        }
        if (mapping.getSyncDirection() == null) {
            mapping.setSyncDirection(SyncDirection.BIDIRECTIONAL);
        }
        if (mapping.getTrunkConflictPolicy() == null) {
            mapping.setTrunkConflictPolicy(com.gitutility.model.enums.TrunkConflictPolicy.ISOLATE);
        }
        if (mapping.getBranchPattern() == null || mapping.getBranchPattern().isBlank()) {
            mapping.setBranchPattern("*");
        }

        validateNoRepositoryCollisions(null, mapping);
        scmCredentialService.requireBoundIfGithub(mapping);

        RepoMapping saved = mappingRepository.save(mapping);

        // Automatically trigger initial full bootstrap mirror synchronization
        if (saved.isActive()) {
            try {
                triggerInitialBootstrapSync(saved);
            } catch (Exception e) {
                log.error("Failed to enqueue initial bootstrap sync for pair '{}': {}", saved.getName(), e.getMessage());
            }
        }

        return saved;
    }

    public SyncJob triggerInitialBootstrapSync(RepoMapping mapping) {
        log.info("Triggering initial full bootstrap mirror synchronization for pair: {}", mapping.getName());

        String sourceRepo = mapping.getRepoAUrl();
        String targetRepo = mapping.getRepoBUrl();

        if (mapping.getSyncDirection() == SyncDirection.UNIDIRECTIONAL_B_TO_A) {
            sourceRepo = mapping.getRepoBUrl();
            targetRepo = mapping.getRepoAUrl();
        }

        SyncJob job = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(sourceRepo)
                .targetRepo(targetRepo)
                .ref(null) // null ref triggers full mirror (+refs/heads/* and +refs/tags/*) in GitSyncEngine
                .branch("* (All Branches & Tags)")
                .commitMessage("Initial full repository bootstrap synchronization")
                .author("System Bootstrap")
                .status(SyncStatus.QUEUED)
                .triggerType(TriggerType.INITIAL_BOOTSTRAP)
                .createdAt(Instant.now())
                .build();

        job = syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);

        queueProducerService.enqueueSyncJob(
                mapping,
                job,
                sourceRepo,
                targetRepo,
                null,
                "*",
                null,
                null,
                job.getCommitMessage(),
                job.getAuthor(),
                TriggerType.INITIAL_BOOTSTRAP
        );

        return job;
    }

    public RepoMapping updateMapping(Long id, RepoMapping updated) {
        RepoMapping existing = mappingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found with id: " + id));

        if (updated.getName() != null && !updated.getName().isBlank()) {
            existing.setName(updated.getName());
        } else if (updated.getRepoAUrl() != null) {
            existing.setName(deriveNameFromUrl(updated.getRepoAUrl()));
        }
        existing.setRepoAUrl(updated.getRepoAUrl());
        existing.setRepoBUrl(updated.getRepoBUrl());
        if (updated.getTokenA() != null && !updated.getTokenA().isBlank()) {
            existing.setTokenA(updated.getTokenA());
        }
        if (updated.getTokenB() != null && !updated.getTokenB().isBlank()) {
            existing.setTokenB(updated.getTokenB());
        }
        existing.setBranchPattern(updated.getBranchPattern());
        existing.setWebhookSecret(updated.getWebhookSecret());
        existing.setSyncDirection(updated.getSyncDirection());
        if (updated.getTrunkConflictPolicy() != null) {
            existing.setTrunkConflictPolicy(updated.getTrunkConflictPolicy());
        }
        existing.setActive(updated.isActive());
        if (updated.getStorageTier() != null) {
            existing.setStorageTier(updated.getStorageTier());
        }
        if (updated.getSourceProvider() != null) {
            existing.setSourceProvider(updated.getSourceProvider());
        }
        if (updated.getTargetProvider() != null) {
            existing.setTargetProvider(updated.getTargetProvider());
        }
        if (updated.getSourceVisibility() != null) {
            existing.setSourceVisibility(updated.getSourceVisibility());
        }
        if (updated.getTargetVisibility() != null) {
            existing.setTargetVisibility(updated.getTargetVisibility());
        }
        if (updated.getSourceCredentialId() != null) {
            existing.setSourceCredentialId(updated.getSourceCredentialId());
        }
        if (updated.getTargetCredentialId() != null) {
            existing.setTargetCredentialId(updated.getTargetCredentialId());
        }

        validateNoRepositoryCollisions(id, existing);
        scmCredentialService.requireBoundIfGithub(existing);

        return mappingRepository.save(existing);
    }

    /**
     * Validates that neither the source nor the destination repository is already part of another active mirror pair.
     * In Git mirror synchronization, sharing a repository across multiple pairs leads to destructive commit DAG clobbering,
     * non-fast-forward push rejections, and accidental branch pruning.
     */
    public void validateNoRepositoryCollisions(Long mappingId, RepoMapping candidate) {
        if (!candidate.isActive()) {
            return;
        }

        String candA = normalizeRepoKey(candidate.getRepoAUrl());
        String candB = normalizeRepoKey(candidate.getRepoBUrl());

        if (candA.isEmpty() || candB.isEmpty()) {
            throw new IllegalArgumentException("Both Source and Destination repository URLs are required.");
        }
        if (candA.equalsIgnoreCase(candB)) {
            throw new IllegalArgumentException(
                    "Source and Destination cannot be the exact same repository (" + candidate.getRepoAUrl() + ")."
            );
        }

        List<RepoMapping> allMappings = mappingRepository.findAll();
        for (RepoMapping existing : allMappings) {
            if (mappingId != null && existing.getId().equals(mappingId)) {
                continue;
            }
            if (!existing.isActive()) {
                continue;
            }

            String existA = normalizeRepoKey(existing.getRepoAUrl());
            String existB = normalizeRepoKey(existing.getRepoBUrl());

            if (candA.equalsIgnoreCase(existA) || candA.equalsIgnoreCase(existB)) {
                throw new IllegalArgumentException(String.format(
                        "Repository '%s' is already participating in active mirror pair '%s'. " +
                        "A repository cannot be mapped to multiple pairs simultaneously to prevent destructive history collisions.",
                        candidate.getRepoAUrl(), existing.getName()
                ));
            }
            if (candB.equalsIgnoreCase(existA) || candB.equalsIgnoreCase(existB)) {
                throw new IllegalArgumentException(String.format(
                        "Repository '%s' is already participating in active mirror pair '%s'. " +
                        "A repository cannot be mapped to multiple pairs simultaneously to prevent destructive history collisions.",
                        candidate.getRepoBUrl(), existing.getName()
                ));
            }
        }
    }

    /**
     * Canonical key normalization for repository URLs.
     * Strips protocols (https, http, ssh, git), auth credentials, .git extensions, and trailing slashes.
     */
    public static String normalizeRepoKey(String url) {
        if (url == null || url.isBlank()) return "";
        String s = url.trim().toLowerCase();
        s = s.replaceAll("/+$", "");
        s = s.replaceAll("\\.git$", "");
        s = s.replaceAll("^(https?|ssh|git)://", "");
        s = s.replaceAll("^git@([^:]+):", "$1/");
        s = s.replaceAll("^[^@/]+@", "");
        return s;
    }

    public static boolean sameRepo(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizeRepoKey(left).equals(normalizeRepoKey(right));
    }

    private String deriveNameFromUrl(String url) {
        if (url == null || url.isBlank()) return "repo-" + System.currentTimeMillis();
        try {
            String cleaned = url.replaceAll("\\.git$", "").replaceAll("/+$", "");
            int lastSlash = cleaned.lastIndexOf('/');
            if (lastSlash != -1 && lastSlash < cleaned.length() - 1) {
                return cleaned.substring(lastSlash + 1);
            }
            return cleaned;
        } catch (Exception e) {
            return "mirror-repo";
        }
    }

    public void deleteMapping(Long id) {
        mappingRepository.deleteById(id);
    }

    public SyncJob triggerManualSync(Long mappingId, String branch, String direction) {
        return triggerManualSync(mappingId, branch, direction, false);
    }

    public SyncJob triggerManualSync(Long mappingId, String branch, String direction, boolean overwriteFromSource) {
        return triggerManualSync(mappingId, branch, direction, overwriteFromSource, false);
    }

    public SyncJob triggerManualSync(Long mappingId, String branch, String direction,
                                     boolean overwriteFromSource, boolean startFresh) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found with id: " + mappingId));

        if (startFresh) {
            syncCheckpointService.resetPairProgress(mappingId);
        }

        boolean isFullMirror = (branch == null || branch.isBlank() || "*".equals(branch.trim()) || "ALL".equalsIgnoreCase(branch.trim()));
        String actualBranch = isFullMirror ? "* (All Branches & Tags)" : branch.trim();
        String ref = isFullMirror ? null : "refs/heads/" + actualBranch;

        String sourceRepo = mapping.getRepoAUrl();
        String targetRepo = mapping.getRepoBUrl();

        if ("B_TO_A".equalsIgnoreCase(direction)) {
            sourceRepo = mapping.getRepoBUrl();
            targetRepo = mapping.getRepoAUrl();
        }

        String commitMsg;
        if (overwriteFromSource && !isFullMirror) {
            commitMsg = "Overwrite destination branch " + actualBranch + " from source (force-push)";
        } else if (isFullMirror) {
            commitMsg = overwriteFromSource
                    ? "Full mirror overwrite from source (force-push diverged trunks)"
                    : "Full mirror synchronization triggered via UI";
        } else {
            commitMsg = "Manual mirror synchronization for branch: " + actualBranch;
        }

        SyncJob job = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(sourceRepo)
                .targetRepo(targetRepo)
                .ref(ref)
                .branch(actualBranch)
                .commitMessage(commitMsg)
                .author("Admin")
                .status(SyncStatus.QUEUED)
                .triggerType(TriggerType.MANUAL)
                .createdAt(Instant.now())
                .build();

        job = syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);

        queueProducerService.enqueueSyncJob(
                mapping,
                job,
                sourceRepo,
                targetRepo,
                ref,
                isFullMirror ? "*" : actualBranch,
                null,
                null,
                job.getCommitMessage(),
                job.getAuthor(),
                TriggerType.MANUAL,
                overwriteFromSource,
                startFresh
        );

        return job;
    }
}
