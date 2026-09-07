package com.gitutility.service;

import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RefOrigin;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RefOriginRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent origination for branch heads: which pair side first produced a ref,
 * plus synthetic dest-only fork-PR heads that must never reverse-sync onto origin.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefOriginService {

    private static final List<String> AUTOMATED_BOT_BRANCH_PREFIXES = List.of(
            "dependabot/",
            "renovate/",
            "snyk-bot/",
            "greenkeeper/"
    );

    private final RefOriginRepository refOriginRepository;
    private final PrMappingRepository prMappingRepository;

    public static String canonicalHeadRef(String branchOrRef) {
        if (branchOrRef == null || branchOrRef.isBlank()) {
            return null;
        }
        String name = branchOrRef.trim();
        if (name.startsWith("refs/heads/")) {
            return name;
        }
        if (name.startsWith("refs/")) {
            return name;
        }
        return "refs/heads/" + name;
    }

    public static String branchName(String branchOrRef) {
        if (branchOrRef == null) {
            return null;
        }
        String name = branchOrRef.trim();
        if (name.startsWith("refs/heads/")) {
            return name.substring("refs/heads/".length());
        }
        return name;
    }

    public static boolean isProtectedTrunk(String branchOrRef) {
        String branch = branchName(branchOrRef);
        if (branch == null) {
            return false;
        }
        String b = branch.toLowerCase(Locale.ROOT);
        return "main".equals(b) || "master".equals(b);
    }

    /** Dest-only head used to materialize a fork PR without colliding with real dest branches. */
    public static boolean isSyntheticForkPrHead(String branchOrRef) {
        String branch = branchName(branchOrRef);
        return branch != null && branch.startsWith("fork-pr-");
    }

    public static boolean isSyncConflictBranch(String branchOrRef) {
        String branch = branchName(branchOrRef);
        return branch != null && branch.startsWith("sync-conflict/");
    }

    /** Branches created by dependency bots on a mirror; never reverse-sync to origin. */
    public static boolean isAutomatedBotBranch(String branchOrRef) {
        String branch = branchName(branchOrRef);
        if (branch == null || branch.isBlank()) {
            return false;
        }
        String lower = branch.toLowerCase(Locale.ROOT);
        return AUTOMATED_BOT_BRANCH_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /** Public upstream (A) → private mirror (B) disaster-recovery topology. */
    public static boolean isPublicToPrivateBackupTopology(RepoMapping mapping) {
        if (mapping == null) {
            return false;
        }
        return mapping.getSourceVisibility() == RepoVisibility.PUBLIC
                && mapping.getTargetVisibility() == RepoVisibility.PRIVATE;
    }

    public static boolean isDeletedSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return false;
        }
        String s = sha.trim();
        if ("null".equalsIgnoreCase(s)) {
            return true;
        }
        return s.chars().allMatch(c -> c == '0');
    }

    public static PairSide inboundSide(RepoMapping mapping, String inboundRepoUrl) {
        if (mapping == null) {
            return PairSide.A;
        }
        if (inboundRepoUrl != null && RepoMappingService.sameRepo(inboundRepoUrl, mapping.getRepoBUrl())) {
            return PairSide.B;
        }
        return PairSide.A;
    }

    public static PairSide sourceSide(RepoMapping mapping, String sourceRepoUrl) {
        if (mapping == null) {
            return PairSide.A;
        }
        if (sourceRepoUrl != null && RepoMappingService.sameRepo(sourceRepoUrl, mapping.getRepoBUrl())) {
            return PairSide.B;
        }
        return PairSide.A;
    }

    public PairSide originOf(Long mappingId, String branchOrRef) {
        String ref = canonicalHeadRef(branchOrRef);
        if (mappingId == null || ref == null) {
            return null;
        }
        return refOriginRepository.findByMappingIdAndRefName(mappingId, ref)
                .map(row -> PairSide.fromString(row.getOriginSide()))
                .orElse(null);
    }

    public boolean isForkPrHead(Long mappingId, String branchOrRef) {
        if (mappingId == null) {
            return false;
        }
        String branch = branchName(branchOrRef);
        if (branch == null || branch.isBlank()) {
            return false;
        }
        if (isSyntheticForkPrHead(branchOrRef)) {
            return true;
        }
        List<PrMapping> mappings = prMappingRepository.findByMappingId(mappingId);
        for (PrMapping pm : mappings) {
            if (pm.getHeadBranch() != null && branch.equals(pm.getHeadBranch()) && pm.isForkPrHead()) {
                return true;
            }
        }
        return false;
    }

    public Set<String> forkPrHeadBranches(Long mappingId) {
        Set<String> names = new HashSet<>();
        if (mappingId == null) {
            return names;
        }
        for (PrMapping pm : prMappingRepository.findByMappingId(mappingId)) {
            if (pm.isForkPrHead() && pm.getHeadBranch() != null && !pm.getHeadBranch().isBlank()) {
                names.add(pm.getHeadBranch());
            }
        }
        return names;
    }

    /**
     * First successful push of a head from {@code side} records origination.
     * Synthetic fork-PR dest heads are never recorded as originated on the replica.
     */
    public void recordIfAbsent(Long mappingId, String branchOrRef, PairSide side) {
        String ref = canonicalHeadRef(branchOrRef);
        if (mappingId == null || ref == null || !ref.startsWith("refs/heads/") || side == null) {
            return;
        }
        if (isForkPrHead(mappingId, ref)) {
            return;
        }
        if (refOriginRepository.findByMappingIdAndRefName(mappingId, ref).isPresent()) {
            return;
        }
        refOriginRepository.save(RefOrigin.builder()
                .mappingId(mappingId)
                .refName(ref)
                .originSide(side.name())
                .createdAt(Instant.now())
                .build());
        log.debug("Recorded ref origin {} on mapping {} as {}", ref, mappingId, side);
    }

    /**
     * After inspecting dest remotes: dest-only heads matching a PR head are synthetic fork
     * materializations (legacy rows get {@code forkPrHead} backfilled). Remaining dest-only
     * heads originated on the destination side of this job.
     */
    public void observeDestinationHeads(RepoMapping mapping, Set<String> localHeadBranches,
                                        Set<String> destHeadBranches, PairSide destSide) {
        if (mapping == null || destHeadBranches == null || destSide == null) {
            return;
        }
        Set<String> local = localHeadBranches != null ? localHeadBranches : Set.of();
        Set<String> destOnly = new HashSet<>();
        for (String name : destHeadBranches) {
            if (name != null && !local.contains(name)) {
                destOnly.add(name);
            }
        }
        if (destOnly.isEmpty()) {
            return;
        }
        List<PrMapping> prs = prMappingRepository.findByMappingId(mapping.getId());
        Set<String> prHeads = new HashSet<>();
        for (PrMapping pm : prs) {
            if (pm.getHeadBranch() != null) {
                prHeads.add(pm.getHeadBranch());
            }
        }
        for (PrMapping pm : prs) {
            if (pm.getHeadBranch() != null && destOnly.contains(pm.getHeadBranch()) && !pm.isForkPrHead()) {
                pm.setForkPrHead(true);
                if (pm.getOriginSide() == null || pm.getOriginSide().isBlank()) {
                    pm.setOriginSide(PairSide.A.name());
                }
                pm.setUpdatedAt(Instant.now());
                prMappingRepository.save(pm);
                log.info("Backfilled forkPrHead on mapping {} PR #{} head '{}'",
                        mapping.getId(), pm.getSourcePrNumber(), pm.getHeadBranch());
            }
        }
        for (String name : destOnly) {
            if (prHeads.contains(name)) {
                continue;
            }
            recordIfAbsent(mapping.getId(), name, destSide);
        }
    }

    /**
     * Replica events (inbound side is not the origin) must not overwrite or delete origin.
     * Unknown origin is allowed through so dest-originated branches can be recorded on first push.
     */
    public boolean isReplicaEvent(Long mappingId, String branchOrRef, PairSide inbound) {
        PairSide origin = originOf(mappingId, branchOrRef);
        return origin != null && inbound != null && origin != inbound;
    }

    /**
     * For public→private backup pairs, mirror-side (B) webhooks must not propagate novel or
     * mirror-originated branches back to the public upstream.
     */
    public boolean shouldBlockReplicaInboundWebhook(RepoMapping mapping, String branchOrRef, PairSide inbound) {
        if (mapping == null || inbound != PairSide.B || !isPublicToPrivateBackupTopology(mapping)) {
            return false;
        }
        PairSide origin = originOf(mapping.getId(), branchOrRef);
        return origin == null || origin == PairSide.B;
    }

    /**
     * Heads originated on the destination of this job, or synthetic fork heads when pushing
     * toward repo A, must not be included in push specs.
     */
    public boolean shouldOmitHeadPush(RepoMapping mapping, PairSide sourceSide, PairSide destSide, String branchOrRef) {
        if (mapping == null || branchOrRef == null) {
            return false;
        }
        String branch = branchName(branchOrRef);
        if ((isSyncConflictBranch(branch) || isSyntheticForkPrHead(branch)) && destSide == PairSide.A) {
            return true;
        }
        if (destSide == PairSide.A && isForkPrHead(mapping.getId(), branch)) {
            return true;
        }
        PairSide origin = originOf(mapping.getId(), branchOrRef);
        return origin != null && sourceSide != null && origin != sourceSide;
    }

    public boolean shouldPropagateDelete(RepoMapping mapping, PairSide sourceSide, String branchOrRef) {
        if (mapping == null || isProtectedTrunk(branchOrRef) || isSyncConflictBranch(branchOrRef)
                || isSyntheticForkPrHead(branchOrRef)) {
            return false;
        }
        if (isForkPrHead(mapping.getId(), branchOrRef) && sourceSide == PairSide.B) {
            return false;
        }
        PairSide origin = originOf(mapping.getId(), branchOrRef);
        if (origin == null) {
            return sourceSide == PairSide.A;
        }
        return origin == sourceSide;
    }
}
