package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictKind;
import com.gitutility.model.enums.ConflictStatus;
import com.gitutility.model.enums.TrunkConflictPolicy;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.SyncConflictRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class SyncConflictService {

    private final SyncConflictRepository conflictRepository;
    private final ScmProviderFacade scmProviderFacade;
    private final ActionsTriggerSuppressionService actionsTriggerSuppressionService;

    public SyncConflictService(
            SyncConflictRepository conflictRepository,
            ScmProviderFacade scmProviderFacade,
            @Lazy ActionsTriggerSuppressionService actionsTriggerSuppressionService) {
        this.conflictRepository = conflictRepository;
        this.scmProviderFacade = scmProviderFacade;
        this.actionsTriggerSuppressionService = actionsTriggerSuppressionService;
    }

    public List<SyncConflict> listForMapping(Long mappingId) {
        if (mappingId == null) {
            return List.of();
        }
        return conflictRepository.findByMappingIdOrderByCreatedAtDesc(mappingId);
    }

    public List<SyncConflict> listOpen(Long mappingId) {
        if (mappingId == null) {
            return List.of();
        }
        return conflictRepository.findByMappingIdAndStatusOrderByCreatedAtDesc(mappingId, ConflictStatus.OPEN);
    }

    public SyncConflict recordGitRefConflict(Long mappingId, Long jobId, ConflictKind kind,
                                             TrunkConflictPolicy policy, String refName,
                                             String sourceSha, String destSha, String isolatedBranch,
                                             String destRepo, String message) {
        if (mappingId == null) {
            return null;
        }
        Optional<SyncConflict> existing = destSha != null && refName != null
                ? conflictRepository.findByMappingIdAndRefNameAndDestShaAndStatus(
                        mappingId, refName, destSha, ConflictStatus.OPEN)
                : Optional.empty();
        if (existing.isEmpty() && destSha != null && refName != null) {
            existing = conflictRepository.findByMappingIdAndRefNameAndDestShaAndStatus(
                    mappingId, refName, destSha, ConflictStatus.PR_OPENED);
        }
        if (existing.isPresent()) {
            SyncConflict row = existing.get();
            if (isolatedBranch != null) {
                row.setIsolatedBranch(isolatedBranch);
            }
            if (jobId != null) {
                row.setJobId(jobId);
            }
            return conflictRepository.save(row);
        }
        SyncConflict row = SyncConflict.builder()
                .mappingId(mappingId)
                .jobId(jobId)
                .kind(kind != null ? kind : ConflictKind.GIT_REF)
                .status(ConflictStatus.OPEN)
                .policyApplied(policy)
                .refName(refName)
                .sourceSha(sourceSha)
                .destSha(destSha)
                .isolatedBranch(isolatedBranch)
                .destRepo(destRepo)
                .message(message)
                .createdAt(Instant.now())
                .build();
        return conflictRepository.save(row);
    }

    public SyncConflict recordMetadataConflict(Long mappingId, Long jobId, String refName,
                                               String destRepo, String originTitle, String replicaTitle,
                                               String message) {
        if (mappingId == null) {
            return null;
        }
        SyncConflict row = SyncConflict.builder()
                .mappingId(mappingId)
                .jobId(jobId)
                .kind(ConflictKind.METADATA)
                .status(ConflictStatus.OPEN)
                .refName(refName)
                .destRepo(destRepo)
                .originTitle(truncate(originTitle, 1000))
                .replicaTitle(truncate(replicaTitle, 1000))
                .message(message)
                .createdAt(Instant.now())
                .build();
        return conflictRepository.save(row);
    }

    /**
     * Opens a PR on the destination from the isolated conflict branch onto the original trunk.
     */
    public SyncConflict openConflictPr(SyncConflict conflict, RepoMapping mapping, String destRepoUrl) {
        if (conflict == null || mapping == null || destRepoUrl == null) {
            return conflict;
        }
        String isolated = conflict.getIsolatedBranch();
        String base = RefOriginService.branchName(conflict.getRefName());
        if (isolated == null || isolated.isBlank() || base == null || base.isBlank()) {
            return conflict;
        }
        String fullName = scmProviderFacade.parseRepoFullName(destRepoUrl);
        if (fullName == null) {
            return conflict;
        }
        ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(destRepoUrl);
        String srcShort = shortSha(conflict.getSourceSha());
        String destShort = shortSha(conflict.getDestSha());
        String title = "[sync-conflict] Merge isolated '" + isolated + "' into " + base;
        String body = "GitMirror Hub isolated a non-fast-forward update on `" + base + "`.\n\n"
                + "- Incoming tip: `" + srcShort + "`\n"
                + "- Destination tip (kept): `" + destShort + "`\n"
                + "- Isolated branch: `" + isolated + "`\n\n"
                + "Review and merge this PR to accept the incoming history, or close it to keep the destination tip.";
        Long credId = credentialForDest(mapping, destRepoUrl);
        Long prNumber;
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credId)) {
            prNumber = adapter.createPullRequest(fullName, title, body, isolated, base);
        }
        if (prNumber != null) {
            if (actionsTriggerSuppressionService != null) {
                try {
                    actionsTriggerSuppressionService.suppressAfterWrite(destRepoUrl, conflict.getJobId());
                } catch (Exception e) {
                    log.debug("Actions suppression after conflict PR on {}: {}", destRepoUrl, e.getMessage());
                }
            }
            conflict.setConflictPrNumber(prNumber);
            conflict.setStatus(ConflictStatus.PR_OPENED);
            if (conflict.getDestRepo() == null || conflict.getDestRepo().isBlank()) {
                conflict.setDestRepo(destRepoUrl);
            }
            log.info("Opened conflict PR #{} on {} for isolated branch {}", prNumber, fullName, isolated);
            return conflictRepository.save(conflict);
        }
        log.warn("Could not open conflict PR on {} for isolated branch {}", fullName, isolated);
        return conflict;
    }

    public Optional<SyncConflict> resolve(Long mappingId, Long conflictId) {
        if (mappingId == null || conflictId == null) {
            return Optional.empty();
        }
        return conflictRepository.findById(conflictId)
                .filter(row -> mappingId.equals(row.getMappingId()))
                .map(row -> {
                    row.setStatus(ConflictStatus.RESOLVED);
                    row.setResolvedAt(Instant.now());
                    return conflictRepository.save(row);
                });
    }

    public Optional<SyncConflict> retryOpenPr(Long mappingId, Long conflictId, RepoMapping mapping, String destRepoUrl) {
        if (mappingId == null || conflictId == null) {
            return Optional.empty();
        }
        return conflictRepository.findById(conflictId)
                .filter(row -> mappingId.equals(row.getMappingId()))
                .map(row -> openConflictPr(row, mapping, destRepoUrl));
    }

    private static Long credentialForDest(RepoMapping mapping, String destRepoUrl) {
        if (mapping == null || destRepoUrl == null) {
            return null;
        }
        if (RepoMappingService.sameRepo(destRepoUrl, mapping.getRepoBUrl())) {
            return mapping.getTargetCredentialId();
        }
        if (RepoMappingService.sameRepo(destRepoUrl, mapping.getRepoAUrl())) {
            return mapping.getSourceCredentialId();
        }
        return mapping.getTargetCredentialId();
    }

    static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "unknown";
        }
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
