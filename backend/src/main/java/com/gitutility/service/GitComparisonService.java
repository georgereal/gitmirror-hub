package com.gitutility.service;

import com.gitutility.model.dto.DiffInspectOptions;
import com.gitutility.model.dto.MirrorMetadataSnapshot;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.dto.SyncDiffReport.BranchDiffDetail;
import com.gitutility.model.dto.SyncDiffReport.PrSyncDetail;
import com.gitutility.model.dto.SyncDiffReport.LfsSyncSummary;
import com.gitutility.model.dto.SyncDiffReport.TagSyncSummary;
import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitComparisonService {

    @Value("${git-utility.workspace-dir:/tmp/git-utility-mirrors}")
    private String workspaceDir;

    private final RepoMappingRepository mappingRepository;
    private final PrMappingRepository prMappingRepository;
    private final GitLfsSyncService gitLfsSyncService;
    private final StorageTieringService storageTieringService;
    private final com.gitutility.provider.ScmProviderFacade scmProviderFacade;
    private final RefOriginService refOriginService;
    private final PairDiffSnapshotService pairDiffSnapshotService;
    private final DiffInspectionProgressService diffInspectionProgressService;
    private final PairCatchupLedger pairCatchupLedger;
    private final SyncCheckpointService syncCheckpointService;

    private static final long QUICK_DIFF_CACHE_TTL_MS = 45_000;
    private static final long BRANCH_LIST_CACHE_TTL_MS = 120_000;

    private final ConcurrentHashMap<Long, CachedQuickDiff> quickDiffCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BranchListCache> branchListCache = new ConcurrentHashMap<>();

    private record CachedQuickDiff(SyncDiffReport report, long expiresAtMs) {}

    private record BranchListCache(
            List<BranchDiffDetail> actionable,
            List<BranchDiffDetail> inSync,
            SyncDiffReport summary,
            boolean unidirectionalAToB,
            long expiresAtMs) {}

    record PagedBranchSlice(
            List<BranchDiffDetail> rows,
            int offset,
            int limit,
            int filteredTotal,
            boolean hasMore) {}

    private record BranchRefMaps(
            Map<String, ObjectId> sourceBranches,
            Map<String, ObjectId> targetBranches,
            Set<String> uniqueBranchNames,
            int tagAndNoteCount,
            int destTagCount) {}

    public SyncDiffReport computeSyncDiff(Long mappingId) {
        return computeSyncDiff(mappingId, DiffInspectOptions.quick());
    }

    public SyncDiffReport computeSyncDiff(Long mappingId, DiffInspectOptions options) {
        DiffInspectOptions inspect = options != null ? options : DiffInspectOptions.quick();
        boolean quickInspect = !inspect.refresh() && !inspect.includeMetadata();
        long now = System.currentTimeMillis();

        if (!inspect.refresh()) {
            BranchListCache branchCache = branchListCache.get(mappingId);
            if (branchCache != null && branchCache.expiresAtMs() > now && branchCache.summary() != null) {
                return applyBranchPaging(branchCache.summary(), branchCache, inspect);
            }
        }

        if (quickInspect && !inspect.hasBranchPaging()) {
            CachedQuickDiff cached = quickDiffCache.get(mappingId);
            if (cached != null && cached.expiresAtMs() > now) {
                return cached.report();
            }
        }

        SyncDiffReport report = computeSyncDiffUncached(mappingId, inspect);
        if (quickInspect) {
            quickDiffCache.put(mappingId, new CachedQuickDiff(report, now + QUICK_DIFF_CACHE_TTL_MS));
        } else {
            quickDiffCache.remove(mappingId);
            pairDiffSnapshotService.persistFromReport(mappingId, report,
                    inspect.includeMetadata() ? "FULL_REFRESH" : "REFRESH");
            invalidateQuickDiffCache(mappingId);
        }
        return report;
    }

    public void invalidateQuickDiffCache(Long mappingId) {
        if (mappingId != null) {
            quickDiffCache.remove(mappingId);
            branchListCache.remove(mappingId);
        }
    }

    private SyncDiffReport computeSyncDiffUncached(Long mappingId, DiffInspectOptions inspect) {
        DiffInspectionProgressService.Session progress = diffInspectionProgressService.open(mappingId, inspect);
        try {
            return computeSyncDiffUncached(mappingId, inspect, progress);
        } finally {
            progress.close();
        }
    }

    private SyncDiffReport computeSyncDiffUncached(Long mappingId, DiffInspectOptions inspect,
                                                   DiffInspectionProgressService.Session progress) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + mappingId));

        boolean quickInspect = !inspect.refresh() && !inspect.includeMetadata();
        if (quickInspect) {
            pairDiffSnapshotService.backfillFromMirrorFieldsIfMissing(mapping);
            mapping = mappingRepository.findById(mappingId).orElse(mapping);
        }

        SyncDiffReport.SyncDiffReportBuilder reportBuilder = SyncDiffReport.builder()
                .mappingId(mappingId)
                .pairName(mapping.getName())
                .sourceRepo(mapping.getRepoAUrl())
                .targetRepo(mapping.getRepoBUrl())
                .inspectionMode(inspect.refresh() ? "full" : "quick")
                .metadataDeferred(!inspect.includeMetadata());

        List<BranchDiffDetail> branchDetails = new ArrayList<>();
        List<BranchDiffDetail> actionableDetails = new ArrayList<>();
        List<BranchDiffDetail> inSyncDetails = new ArrayList<>();
        int inSyncCount = 0;
        int pendingCount = 0;
        int divergedCount = 0;
        int destOnlyCount = 0;
        int totalBranchNames = 0;
        int sourceBranchCount = 0;
        int destBranchCount = 0;
        boolean unidirectionalAToB = mapping.getSyncDirection()
                == com.gitutility.model.enums.SyncDirection.UNIDIRECTIONAL_A_TO_B;
        boolean deepBranchAnalysis = inspect.refresh() || inspect.includeMetadata();

        File repoDir = storageTieringService != null
                ? storageTieringService.resolveRepoDirectory(mappingId, mapping.getStorageTier())
                : getOrCreateBareRepoDir(mappingId);
        progress.markCurrent(DiffInspectionPipeline.PREPARE);
        BareRepoHousekeeping.prepareRepoDirectory(repoDir);
        progress.markDone(DiffInspectionPipeline.PREPARE);

        try (Git git = initOrOpenBareGit(repoDir, mapping.getRepoAUrl(), mapping.getRepoBUrl())) {
            if (deepBranchAnalysis) {
                GitSyncEngine.normalizeLegacySourceBranchRefs(git);
            }
            CredentialsProvider sourceCreds = createCredentialsProvider(mapping.getRepoAUrl(), mapping.getTokenA());
            CredentialsProvider targetCreds = createCredentialsProvider(mapping.getRepoBUrl(), mapping.getTokenB());

            // 1. Optional network refresh (skipped on quick page load — uses local bare refs only)
            if (inspect.refresh()) {
                try {
                    if (!isSimulationOrTestUrl(mapping.getRepoAUrl())) {
                        progress.markCurrent(DiffInspectionPipeline.FETCH_SOURCE, mapping.getRepoAUrl());
                        git.fetch()
                                .setRemote("source")
                                .setRefSpecs(
                                        new RefSpec("+refs/heads/*:refs/heads/*"),
                                        new RefSpec("+refs/tags/*:refs/tags/*"),
                                        new RefSpec("+refs/notes/*:refs/notes/*")
                                )
                                .setCredentialsProvider(sourceCreds)
                                .setRemoveDeletedRefs(true)
                                .call();
                        progress.markDone(DiffInspectionPipeline.FETCH_SOURCE, "Source refs updated");
                    }
                } catch (Exception e) {
                    log.debug("Fetch source for diff comparison notice: {}", e.getMessage());
                    progress.markDone(DiffInspectionPipeline.FETCH_SOURCE, "Completed with notice");
                }

                try {
                    if (!isSimulationOrTestUrl(mapping.getRepoBUrl())) {
                        progress.markCurrent(DiffInspectionPipeline.FETCH_DEST, mapping.getRepoBUrl());
                        git.fetch()
                                .setRemote("target")
                                .setRefSpecs(
                                        new RefSpec("+refs/heads/*:refs/remotes/target/*"),
                                        new RefSpec("+refs/tags/*:refs/remotes/target-tags/*")
                                )
                                .setCredentialsProvider(targetCreds)
                                .setRemoveDeletedRefs(true)
                                .call();
                        progress.markDone(DiffInspectionPipeline.FETCH_DEST, "Destination refs updated");
                    }
                } catch (Exception e) {
                    log.debug("Fetch target for diff comparison notice: {}", e.getMessage());
                    progress.markDone(DiffInspectionPipeline.FETCH_DEST, "Completed with notice");
                }
                BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
            }

            Repository repository = git.getRepository();
            BranchRefMaps refMaps = loadBranchRefMaps(repository, !inspect.includeMetadata());
            Map<String, ObjectId> sourceBranches = refMaps.sourceBranches();
            Map<String, ObjectId> targetBranches = refMaps.targetBranches();
            Set<String> uniqueBranchNames = refMaps.uniqueBranchNames();

            if (refOriginService != null && deepBranchAnalysis) {
                refOriginService.observeDestinationHeads(
                        mapping, sourceBranches.keySet(), targetBranches.keySet(),
                        com.gitutility.model.enums.PairSide.B);
            }
            Set<String> forkHeads = refOriginService != null
                    ? refOriginService.forkPrHeadBranches(mappingId)
                    : Set.of();

            totalBranchNames = uniqueBranchNames.size();
            sourceBranchCount = sourceBranches.size();
            destBranchCount = targetBranches.size();
            List<String> allBranchNames = new ArrayList<>(uniqueBranchNames);
            Collections.sort(allBranchNames);
            progress.markCurrent(DiffInspectionPipeline.COMPARE_BRANCHES,
                    totalBranchNames + " branch" + (totalBranchNames == 1 ? "" : "es") + " to compare");

            actionableDetails = new ArrayList<>();
            inSyncDetails = new ArrayList<>();

            try (RevWalk revWalk = new RevWalk(repository)) {
                revWalk.setRetainBody(false);
                int branchIndex = 0;
                for (String branchName : allBranchNames) {
                    branchIndex++;
                    ObjectId sourceId = sourceBranches.get(branchName);
                    ObjectId targetId = targetBranches.get(branchName);

                    BranchDiffDetail.BranchDiffDetailBuilder branchBuilder = BranchDiffDetail.builder()
                            .branchName(branchName);

                    if (sourceId != null) {
                        branchBuilder.sourceSha(sourceId.name());
                        branchBuilder.sourceShortSha(sourceId.name().substring(0, Math.min(7, sourceId.name().length())));
                        if (deepBranchAnalysis) {
                            try {
                                RevCommit sc = revWalk.parseCommit(sourceId);
                                branchBuilder.lastCommitMessage(sc.getShortMessage());
                                branchBuilder.lastCommitAuthor(sc.getAuthorIdent().getName());
                            } catch (Exception ignored) {}
                        }
                    }

                    if (targetId != null) {
                        branchBuilder.targetSha(targetId.name());
                        branchBuilder.targetShortSha(targetId.name().substring(0, Math.min(7, targetId.name().length())));
                    }

                    if (sourceId != null && targetId != null) {
                        if (sourceId.equals(targetId)) {
                            branchBuilder.status("IN_SYNC");
                            branchBuilder.aheadCount(0);
                            branchBuilder.behindCount(0);
                            inSyncCount++;
                        } else if (!deepBranchAnalysis) {
                            branchBuilder.status("PENDING_SYNC");
                            branchBuilder.aheadCount(0);
                            branchBuilder.behindCount(0);
                            pendingCount++;
                        } else {
                            try {
                                RevCommit srcCommit = revWalk.parseCommit(sourceId);
                                RevCommit tgtCommit = revWalk.parseCommit(targetId);

                                boolean srcMergedInTgt = revWalk.isMergedInto(srcCommit, tgtCommit);
                                boolean tgtMergedInSrc = revWalk.isMergedInto(tgtCommit, srcCommit);

                                if (tgtMergedInSrc && !srcMergedInTgt) {
                                    int ahead = countCommitsBetween(repository, tgtCommit, srcCommit);
                                    branchBuilder.status("AHEAD");
                                    branchBuilder.aheadCount(ahead);
                                    branchBuilder.behindCount(0);
                                    pendingCount++;
                                } else if (srcMergedInTgt && !tgtMergedInSrc) {
                                    int behind = countCommitsBetween(repository, srcCommit, tgtCommit);
                                    branchBuilder.status("BEHIND");
                                    branchBuilder.aheadCount(0);
                                    branchBuilder.behindCount(behind);
                                    if (unidirectionalAToB) {
                                        inSyncCount++;
                                    } else {
                                        pendingCount++;
                                    }
                                } else {
                                    branchBuilder.status("DIVERGED");
                                    divergedCount++;
                                }
                            } catch (Exception ex) {
                                branchBuilder.status("PENDING_SYNC");
                                pendingCount++;
                            }
                        }
                    } else if (sourceId != null && targetId == null) {
                        branchBuilder.status("TARGET_MISSING");
                        pendingCount++;
                    } else if (sourceId == null && targetId != null) {
                        branchBuilder.status("SOURCE_MISSING");
                        branchBuilder.forkPrHead(forkHeads.contains(branchName));
                        destOnlyCount++;
                    } else if (sourceId == null && targetId == null) {
                        continue;
                    }

                    BranchDiffDetail built = branchBuilder.build();
                    if (isActionableBranchDetail(built.getStatus(), unidirectionalAToB)) {
                        actionableDetails.add(built);
                    } else {
                        inSyncDetails.add(built);
                    }

                    if (branchIndex == 1 || branchIndex % 50 == 0 || branchIndex == allBranchNames.size()) {
                        progress.updateBranchCounts(inSyncCount, pendingCount, divergedCount, destOnlyCount);
                        progress.branchProgress(branchIndex, allBranchNames.size());
                    }
                }
                PagedBranchSlice slice = pageBranches(actionableDetails, inSyncDetails, inspect, unidirectionalAToB);
                branchDetails.addAll(slice.rows());
                reportBuilder.branchesTruncated(slice.hasMore());
                reportBuilder.branchesPageOffset(slice.offset());
                reportBuilder.branchesPageSize(slice.limit());
                reportBuilder.branchesFilteredCount(slice.filteredTotal());
                progress.markDone(DiffInspectionPipeline.COMPARE_BRANCHES,
                        String.format("%d in sync, %d pending, %d diverged, %d dest-only",
                                inSyncCount, pendingCount, divergedCount, destOnlyCount));
            } catch (Exception ex) {
                log.debug("RevWalk branch analysis notice: {}", ex.getMessage());
            }

            // 3. Tags — always list local refs for the Tags tab; full annotated messages only on Refresh Diff
            List<SyncDiffReport.TagDetail> tagDetails = new ArrayList<>();
            int srcTags = refMaps.tagAndNoteCount();
            try (RevWalk revWalk = inspect.includeMetadata() ? new RevWalk(repository) : null) {
                srcTags = 0;
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/tags/")) {
                    srcTags++;
                    String refKey = ref.getName();
                    String tagName = refKey.replace("refs/tags/", "");
                    ObjectId targetObjectId = ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId();
                    String sha = targetObjectId != null ? targetObjectId.name() : "";

                    SyncDiffReport.TagDetail.TagDetailBuilder tagBuilder = SyncDiffReport.TagDetail.builder()
                            .tagName(tagName)
                            .refName(refKey)
                            .targetSha(sha)
                            .targetShortSha(sha.length() >= 7 ? sha.substring(0, 7) : sha)
                            .isAnnotated(ref.getPeeledObjectId() != null);

                    if (revWalk != null) {
                        try {
                            if (ref.getObjectId() != null) {
                                org.eclipse.jgit.revwalk.RevObject revObj = revWalk.parseAny(ref.getObjectId());
                                if (revObj instanceof org.eclipse.jgit.revwalk.RevTag revTag) {
                                    tagBuilder.isAnnotated(true);
                                    tagBuilder.message(revTag.getFullMessage());
                                    if (revTag.getTaggerIdent() != null) {
                                        tagBuilder.taggerName(revTag.getTaggerIdent().getName());
                                        tagBuilder.taggerDate(revTag.getTaggerIdent().getWhen().toString());
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    tagDetails.add(tagBuilder.build());
                }
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/notes/")) {
                    srcTags++;
                    String refKey = ref.getName();
                    ObjectId targetObjectId = ref.getObjectId();
                    String sha = targetObjectId != null ? targetObjectId.name() : "";
                    tagDetails.add(SyncDiffReport.TagDetail.builder()
                            .tagName(refKey.replace("refs/notes/", "note:"))
                            .refName(refKey)
                            .targetSha(sha)
                            .targetShortSha(sha.length() >= 7 ? sha.substring(0, 7) : sha)
                            .isAnnotated(true)
                            .build());
                }
                tagDetails.sort((a, b) -> a.getTagName().compareToIgnoreCase(b.getTagName()));
            } catch (Exception ex) {
                log.debug("Tag extraction notice: {}", ex.getMessage());
            }

            int destTagCount = refMaps.destTagCount();
            if (destTagCount <= 0 && srcTags > 0) {
                // Prefer snapshot / prior dest when local target-tags scan is empty
                int snapDest = pairDiffSnapshotService != null
                        ? Optional.ofNullable(pairDiffSnapshotService.load(mapping))
                        .map(s -> s.getTagsTargetCount())
                        .orElse(0)
                        : 0;
                if (snapDest > 0) {
                    destTagCount = snapDest;
                }
            }
            reportBuilder.tags(TagSyncSummary.builder()
                    .sourceTagsCount(srcTags)
                    .targetTagsCount(destTagCount > 0 ? destTagCount : srcTags)
                    .inSync(srcTags == 0 || (destTagCount > 0 && srcTags == destTagCount) || destTagCount <= 0)
                    .build());
            reportBuilder.tagItems(tagDetails);

            if (!inspect.includeMetadata()) {
                int discovered = PairMirrorSnapshotService.lfsDiscoveredCount(mapping);
                int synced = PairMirrorSnapshotService.lfsSyncedCount(mapping);
                reportBuilder.lfs(LfsSyncSummary.builder()
                        .totalDiscovered(discovered)
                        .syncedCount(synced)
                        .pendingCount(Math.max(0, discovered - synced))
                        .inSync(discovered == 0 || synced >= discovered)
                        .build());
                // Hydrate LFS tab from pair checkpoint so page load is not an empty "0 detected" lie.
                if (syncCheckpointService != null && discovered > 0) {
                    List<GitLfsSyncService.LfsObject> cached = syncCheckpointService.loadDiscoveredLfs(mapping);
                    int itemCap = Math.max(100, Math.min(1000, Math.max(1, inspect.maxBranchDetails())));
                    List<SyncDiffReport.LfsPointerDetail> lfsItems = new ArrayList<>();
                    Set<String> completed = syncCheckpointService.loadCompletedLfsOids(mapping);
                    for (GitLfsSyncService.LfsObject obj : cached) {
                        if (obj == null || obj.oid() == null || lfsItems.size() >= itemCap) {
                            break;
                        }
                        String oid = obj.oid();
                        lfsItems.add(SyncDiffReport.LfsPointerDetail.builder()
                                .filePath(obj.filePath() != null ? obj.filePath() : "lfs-object")
                                .oid(oid)
                                .shortOid(oid.substring(0, Math.min(10, oid.length())))
                                .sizeBytes(obj.size())
                                .formattedSize(formatBytes(obj.size()))
                                .headBranch(obj.headBranch() != null ? obj.headBranch() : "—")
                                .build());
                    }
                    reportBuilder.lfsItems(lfsItems);
                    if (!completed.isEmpty() && synced <= 0) {
                        synced = Math.min(discovered, completed.size());
                        reportBuilder.lfs(LfsSyncSummary.builder()
                                .totalDiscovered(discovered)
                                .syncedCount(synced)
                                .pendingCount(Math.max(0, discovered - synced))
                                .inSync(synced >= discovered)
                                .build());
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Could not compute JGit diff for pair #{}: {}", mappingId, e.getMessage());
            reportBuilder.inspectionError(shortError(e));
        }

        // 5. Pull Requests & REST metadata — only on full inspection
        List<PrSyncDetail> prDetails = new ArrayList<>();
        int totalOpenPrs = 0;
        boolean pullRequestsTruncated = false;
        int mirroredPrsCount = 0;
        if (inspect.includeMetadata()) {
        progress.markCurrent(DiffInspectionPipeline.PR_METADATA, "Fetching open pull requests");
        try {
            List<PrMapping> mappedPrs = prMappingRepository.findByMappingId(mappingId);
            mirroredPrsCount = (int) mappedPrs.stream()
                    .filter(pm -> pm.getTargetPrNumber() != null && pm.getTargetPrNumber() > 0)
                    .count();
            Map<Long, PrMapping> bySourceNumber = new HashMap<>();
            Map<String, PrMapping> byHeadBase = new HashMap<>();
            for (PrMapping pm : mappedPrs) {
                if (pm.getSourcePrNumber() != null) {
                    bySourceNumber.put(pm.getSourcePrNumber(), pm);
                }
                if (pm.getHeadBranch() != null && pm.getBaseBranch() != null) {
                    byHeadBase.putIfAbsent(prHeadBaseKey(pm.getHeadBranch(), pm.getBaseBranch()), pm);
                }
            }

            String sourceFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoAUrl());
            if (sourceFullName != null) {
                var sourceAdapter = scmProviderFacade.getAdapterForUrl(mapping.getRepoAUrl());
                MirrorMetadataSnapshot metadata = null;

                try {
                    metadata = sourceAdapter.fetchMirrorMetadataSnapshot(sourceFullName, 100, 30);
                    totalOpenPrs = metadata.openPrTotalCount();
                    pullRequestsTruncated = metadata.pullRequestsTruncated();
                    List<PrSyncDetail> livePrs = metadata.prPreview();
                    Set<String> targetBranches = new HashSet<>();
                    for (BranchDiffDetail b : branchDetails) {
                        if (b.getTargetSha() != null && !b.getTargetSha().isBlank()) {
                            targetBranches.add(b.getBranchName());
                        }
                    }

                    Set<Long> seenSourceNums = new HashSet<>();
                    for (PrSyncDetail lPr : livePrs) {
                        if (lPr.getSourcePrNumber() != null) {
                            seenSourceNums.add(lPr.getSourcePrNumber());
                        }
                        PrMapping pm = lPr.getSourcePrNumber() != null
                                ? bySourceNumber.get(lPr.getSourcePrNumber())
                                : null;
                        if (pm == null && lPr.getHeadBranch() != null && lPr.getBaseBranch() != null) {
                            pm = byHeadBase.get(prHeadBaseKey(lPr.getHeadBranch(), lPr.getBaseBranch()));
                        }
                        if (pm != null) {
                            lPr.setTargetPrNumber(pm.getTargetPrNumber());
                            lPr.setSynced(true);
                            lPr.setSyncStatus("MIRRORED");
                            lPr.setReason("Replicated to target PR #" + pm.getTargetPrNumber());
                            if (lPr.getTitle() == null || lPr.getTitle().isBlank()) {
                                lPr.setTitle(pm.getTitle());
                            }
                        } else {
                            String head = lPr.getHeadBranch();
                            String base = lPr.getBaseBranch();
                            boolean headOnTarget = head != null && targetBranches.contains(head);
                            if (head != null && head.equalsIgnoreCase(base)) {
                                lPr.setSyncStatus("SKIPPED");
                                lPr.setReason("Head and base branch are identical (" + head + "). No commit delta.");
                            } else if (lPr.isFork()) {
                                lPr.setSyncStatus("PENDING");
                                lPr.setReason("Fork PR: ref will be pushed to target when PR sync runs.");
                            } else if (!headOnTarget) {
                                lPr.setSyncStatus("PENDING");
                                lPr.setReason("Head branch '" + head + "' has not been mirrored to target yet.");
                            } else {
                                lPr.setSyncStatus("PENDING");
                                lPr.setReason("Ready to mirror to target repository.");
                            }
                        }
                        prDetails.add(lPr);
                    }

                    for (PrMapping pm : mappedPrs) {
                        if (pm.getSourcePrNumber() != null && seenSourceNums.contains(pm.getSourcePrNumber())) {
                            continue;
                        }
                        prDetails.add(PrSyncDetail.builder()
                                .sourcePrNumber(pm.getSourcePrNumber())
                                .targetPrNumber(pm.getTargetPrNumber())
                                .title(pm.getTitle() != null ? pm.getTitle() : "Pull Request #" + pm.getSourcePrNumber())
                                .state(pm.getState() != null ? pm.getState() : "open")
                                .headBranch(pm.getHeadBranch())
                                .baseBranch(pm.getBaseBranch())
                                .isSynced(pm.getTargetPrNumber() != null && pm.getTargetPrNumber() > 0)
                                .syncStatus("MIRRORED")
                                .reason("Replicated to target PR #" + pm.getTargetPrNumber())
                                .build());
                    }
                } catch (Exception ignored) {
                    log.debug("Live PR list notice: {}", ignored.getMessage());
                }
                if (prDetails.isEmpty()) {
                    for (PrMapping pm : mappedPrs) {
                        prDetails.add(PrSyncDetail.builder()
                                .sourcePrNumber(pm.getSourcePrNumber())
                                .targetPrNumber(pm.getTargetPrNumber())
                                .title(pm.getTitle() != null ? pm.getTitle() : "Pull Request #" + pm.getSourcePrNumber())
                                .state(pm.getState() != null ? pm.getState() : "open")
                                .headBranch(pm.getHeadBranch())
                                .baseBranch(pm.getBaseBranch())
                                .isSynced(pm.getTargetPrNumber() != null && pm.getTargetPrNumber() > 0)
                                .syncStatus("MIRRORED")
                                .reason("Replicated to target PR #" + pm.getTargetPrNumber())
                                .build());
                    }
                }

                // 5b. Releases summary & Itemized Releases (from same GraphQL snapshot when available)
                String prProgressLabel = pullRequestsTruncated && totalOpenPrs > 0
                        ? prDetails.size() + " shown of " + totalOpenPrs + " open PR(s)"
                        : (totalOpenPrs > 0 ? totalOpenPrs : prDetails.size()) + " open PR(s)";
                progress.markDone(DiffInspectionPipeline.PR_METADATA, prProgressLabel);
                progress.markCurrent(DiffInspectionPipeline.RELEASES, "Fetching releases & tags");
                int releaseCount = 0;
                try {
                    List<SyncDiffReport.ReleaseDetail> releaseItems = metadata != null
                            ? metadata.releases()
                            : sourceAdapter.listReleases(sourceFullName);
                    if (!releaseItems.isEmpty()) {
                        releaseCount = metadata != null && metadata.releaseTotalCount() > 0
                                ? metadata.releaseTotalCount()
                                : releaseItems.size();
                        String latestTag = releaseItems.get(0).getTagName() != null ? releaseItems.get(0).getTagName() : "None";
                        int totalAssets = 0;
                        for (var r : releaseItems) {
                            if (r.getAssets() != null) totalAssets += r.getAssets().size();
                        }
                        reportBuilder.releases(SyncDiffReport.ReleaseSyncSummary.builder()
                                .sourceReleasesCount(releaseCount)
                                .targetReleasesCount(releaseCount)
                                .inSync(true)
                                .latestReleaseTag(latestTag)
                                .totalAssetsCount(totalAssets)
                                .build());
                        reportBuilder.releaseItems(releaseItems);
                    }
                    progress.markDone(DiffInspectionPipeline.RELEASES,
                            releaseCount > 0 ? releaseCount + " release(s)" : "No releases found");
                } catch (Exception ignored) {
                    progress.markDone(DiffInspectionPipeline.RELEASES, "Completed with notice");
                }

                // 5c. CI Check Runs & Commit Statuses for latest commit
                try {
                    String trunkSha = null;
                    for (var b : branchDetails) {
                        if (("main".equalsIgnoreCase(b.getBranchName()) || "master".equalsIgnoreCase(b.getBranchName())) && b.getSourceSha() != null) {
                            trunkSha = b.getSourceSha();
                            break;
                        }
                    }
                    if (trunkSha == null && !branchDetails.isEmpty() && branchDetails.get(0).getSourceSha() != null) {
                        trunkSha = branchDetails.get(0).getSourceSha();
                    }

                    if (trunkSha != null && !trunkSha.isBlank()) {
                        List<SyncDiffReport.CiCheckRunDetail> ciRuns = sourceAdapter.listCiCheckRuns(sourceFullName, trunkSha);
                        if (!ciRuns.isEmpty()) {
                            int succ = (int) ciRuns.stream().filter(c -> "success".equalsIgnoreCase(c.getConclusion())).count();
                            int fail = (int) ciRuns.stream().filter(c -> "failure".equalsIgnoreCase(c.getConclusion())).count();
                            reportBuilder.ciStatuses(SyncDiffReport.CiStatusSummary.builder()
                                    .replicatedStatusesCount(ciRuns.size())
                                    .inSync(fail == 0)
                                    .latestStatusState(fail == 0 ? "success" : "failure")
                                    .latestContext("CI Check Runs (" + succ + "/" + ciRuns.size() + " passed)")
                                    .build());
                            reportBuilder.ciCheckRuns(ciRuns);
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                progress.markDone(DiffInspectionPipeline.PR_METADATA, prDetails.size() + " PR(s) from cache");
                progress.markSkipped(DiffInspectionPipeline.RELEASES, "SCM unavailable");
            }
        } catch (Exception ex) {
            log.debug("PR mapping summary notice: {}", ex.getMessage());
            progress.markDone(DiffInspectionPipeline.PR_METADATA, "Completed with notice");
            progress.markDone(DiffInspectionPipeline.RELEASES, "Skipped");
        }
        } else {
            try {
                for (PrMapping pm : prMappingRepository.findByMappingId(mappingId)) {
                    prDetails.add(PrSyncDetail.builder()
                            .sourcePrNumber(pm.getSourcePrNumber())
                            .targetPrNumber(pm.getTargetPrNumber())
                            .title(pm.getTitle() != null ? pm.getTitle() : "Pull Request #" + pm.getSourcePrNumber())
                            .state(pm.getState() != null ? pm.getState() : "open")
                            .headBranch(pm.getHeadBranch())
                            .baseBranch(pm.getBaseBranch())
                            .isSynced(pm.getTargetPrNumber() != null && pm.getTargetPrNumber() > 0)
                            .syncStatus(pm.getTargetPrNumber() != null ? "MIRRORED" : "PENDING")
                            .reason(pm.getTargetPrNumber() != null
                                    ? "Replicated to target PR #" + pm.getTargetPrNumber()
                                    : "Use Refresh Diff for live PR inspection.")
                            .build());
                }
            } catch (Exception ignored) {
            }
        }

        if (inspect.includeMetadata()) {
            try (Git git = initOrOpenBareGit(repoDir, mapping.getRepoAUrl(), mapping.getRepoBUrl())) {
                populateLfsInspection(git.getRepository(), mapping, mapping.getRepoAUrl(), mapping.getRepoBUrl(),
                        reportBuilder, progress, inspect);
            } catch (Exception e) {
                log.debug("LFS inspection notice for pair #{}: {}", mappingId, e.getMessage());
                reportBuilder.lfs(LfsSyncSummary.builder().totalDiscovered(0).syncedCount(0).pendingCount(0).inSync(true).build());
                progress.markDone(DiffInspectionPipeline.LFS, "Completed with notice");
            }
        }

        if (reportBuilder.build().getReleases() == null) {
            reportBuilder.releases(SyncDiffReport.ReleaseSyncSummary.builder()
                    .sourceReleasesCount(0)
                    .targetReleasesCount(0)
                    .inSync(true)
                    .latestReleaseTag("None")
                    .totalAssetsCount(0)
                    .build());
        }

        if (reportBuilder.build().getCiStatuses() == null) {
            reportBuilder.ciStatuses(SyncDiffReport.CiStatusSummary.builder()
                    .replicatedStatusesCount(0)
                    .inSync(true)
                    .latestStatusState("unknown")
                    .latestContext(inspect.includeMetadata()
                            ? "continuous-integration/gitmirror"
                            : "Use Refresh Diff for CI metadata")
                    .build());
        }

        String overall = "IN_SYNC";
        if (divergedCount > 0) {
            overall = "DIVERGED";
        } else if (pendingCount > 0) {
            overall = "PENDING_SYNC";
        }

        SyncDiffReport.SyncDiffReportBuilder finalBuilder = reportBuilder
                .overallStatus(overall)
                .totalBranchesCount(totalBranchNames)
                .sourceBranchesCount(sourceBranchCount)
                .destBranchesCount(destBranchCount)
                .inSyncBranchesCount(inSyncCount)
                .pendingBranchesCount(pendingCount)
                .divergedBranchesCount(divergedCount)
                .destOnlyBranchesCount(destOnlyCount)
                .branches(branchDetails)
                .pullRequests(prDetails)
                .totalOpenPrsCount(totalOpenPrs)
                .pullRequestsTruncated(pullRequestsTruncated)
                .mirroredPrsCount(mirroredPrsCount)
                .destOpenPrsCount(mirroredPrsCount);

        if (quickInspect && pairDiffSnapshotService.load(mapping) != null) {
            pairDiffSnapshotService.applyMetadataToReport(finalBuilder, mapping);
        }

        SyncDiffReport built = finalBuilder.build();
        branchListCache.put(mappingId, new BranchListCache(
                List.copyOf(actionableDetails),
                List.copyOf(inSyncDetails),
                built.toBuilder().branches(List.of()).build(),
                unidirectionalAToB,
                System.currentTimeMillis() + BRANCH_LIST_CACHE_TTL_MS));
        return built;
    }

    static PagedBranchSlice pageBranches(List<BranchDiffDetail> actionable,
                                         List<BranchDiffDetail> inSync,
                                         DiffInspectOptions inspect,
                                         boolean unidirectionalAToB) {
        List<BranchDiffDetail> ordered = buildBranchDetailList(actionable, inSync, Integer.MAX_VALUE);
        String search = inspect.branchSearch();
        String statusFilter = inspect.branchStatus();
        List<BranchDiffDetail> filtered = new ArrayList<>();
        for (BranchDiffDetail detail : ordered) {
            if (!matchesBranchSearch(detail, search)) {
                continue;
            }
            if (!matchesBranchStatusFilter(detail, statusFilter, unidirectionalAToB)) {
                continue;
            }
            filtered.add(detail);
        }
        int limit = Math.max(1, inspect.maxBranchDetails());
        int offset = Math.max(0, inspect.branchOffset());
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + limit, filtered.size());
        return new PagedBranchSlice(filtered.subList(from, to), offset, limit, filtered.size(), to < filtered.size());
    }

    private SyncDiffReport applyBranchPaging(SyncDiffReport summary, BranchListCache cache, DiffInspectOptions inspect) {
        PagedBranchSlice slice = pageBranches(cache.actionable(), cache.inSync(), inspect, cache.unidirectionalAToB());
        return summary.toBuilder()
                .branches(slice.rows())
                .branchesTruncated(slice.hasMore())
                .branchesPageOffset(slice.offset())
                .branchesPageSize(slice.limit())
                .branchesFilteredCount(slice.filteredTotal())
                .build();
    }

    static boolean matchesBranchSearch(BranchDiffDetail detail, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        return detail.getBranchName() != null && detail.getBranchName().toLowerCase(Locale.ROOT).contains(needle);
    }

    static boolean matchesBranchStatusFilter(BranchDiffDetail detail, String filter, boolean unidirectionalAToB) {
        if (filter == null || filter.isBlank() || "ALL".equalsIgnoreCase(filter)) {
            return true;
        }
        String status = detail.getStatus();
        return switch (filter.toUpperCase(Locale.ROOT)) {
            case "IN_SYNC" -> "IN_SYNC".equals(status);
            case "PENDING" -> "PENDING_SYNC".equals(status)
                    || "TARGET_MISSING".equals(status)
                    || "AHEAD".equals(status)
                    || ("BEHIND".equals(status) && !unidirectionalAToB);
            case "DIVERGED" -> "DIVERGED".equals(status);
            case "DEST_ONLY" -> "SOURCE_MISSING".equals(status);
            case "ACTIONABLE" -> isActionableBranchDetail(status, unidirectionalAToB);
            default -> true;
        };
    }

    static List<String> selectBranchesForAnalysis(Set<String> uniqueBranchNames, int maxBranches) {
        int cap = Math.max(1, maxBranches);
        List<String> ordered = new ArrayList<>();
        for (String trunk : List.of("main", "master", "trunk")) {
            if (uniqueBranchNames.contains(trunk)) {
                ordered.add(trunk);
            }
        }
        List<String> rest = new ArrayList<>(uniqueBranchNames);
        rest.removeAll(ordered);
        Collections.sort(rest);
        for (String branch : rest) {
            if (ordered.size() >= cap) {
                break;
            }
            ordered.add(branch);
        }
        return ordered;
    }

    static boolean isActionableBranchDetail(String status, boolean unidirectionalAToB) {
        if (status == null || "IN_SYNC".equals(status)) {
            return false;
        }
        if ("BEHIND".equals(status) && unidirectionalAToB) {
            return false;
        }
        return true;
    }

    static List<BranchDiffDetail> buildBranchDetailList(List<BranchDiffDetail> actionable,
                                                        List<BranchDiffDetail> inSync,
                                                        int maxDetailRows) {
        int cap = Math.max(1, maxDetailRows);
        List<BranchDiffDetail> result = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (BranchDiffDetail detail : actionable) {
            if (result.size() >= cap) {
                break;
            }
            result.add(detail);
            included.add(detail.getBranchName());
        }
        for (String trunk : List.of("main", "master", "trunk")) {
            if (result.size() >= cap) {
                break;
            }
            for (BranchDiffDetail detail : inSync) {
                if (trunk.equalsIgnoreCase(detail.getBranchName()) && included.add(detail.getBranchName())) {
                    result.add(detail);
                    break;
                }
            }
        }
        for (BranchDiffDetail detail : inSync) {
            if (result.size() >= cap) {
                break;
            }
            if (included.add(detail.getBranchName())) {
                result.add(detail);
            }
        }
        return result;
    }

    private BranchRefMaps loadBranchRefMaps(Repository repository, boolean countTagsOnly) throws IOException {
        Set<String> uniqueBranchNames = BareRepoHousekeeping.listHeadBranchNames(repository);
        Map<String, ObjectId> sourceBranches = new HashMap<>();
        Map<String, ObjectId> targetBranches = new HashMap<>();
        for (String branch : uniqueBranchNames) {
            ObjectId sourceId = repository.resolve("refs/heads/" + branch);
            if (sourceId == null) {
                sourceId = repository.resolve("refs/remotes/source/" + branch);
            }
            if (sourceId != null) {
                sourceBranches.put(branch, sourceId);
            }
            ObjectId targetId = repository.resolve("refs/remotes/target/" + branch);
            if (targetId != null) {
                targetBranches.put(branch, targetId);
            }
        }

        int tagCount = countTagAndNoteRefs(repository);
        int destTagCount = BareRepoHousekeeping.listRefSuffixesWithPrefix(repository, "refs/remotes/target-tags/").size();
        return new BranchRefMaps(sourceBranches, targetBranches, uniqueBranchNames, tagCount, destTagCount);
    }

    private int countTagAndNoteRefs(Repository repository) throws IOException {
        File packed = new File(repository.getDirectory(), "packed-refs");
        int count = 0;
        boolean countedFromPacked = false;
        if (packed.isFile()) {
            for (String line : Files.readAllLines(packed.toPath())) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) {
                    continue;
                }
                int space = line.indexOf(' ');
                if (space <= 0) {
                    continue;
                }
                String ref = line.substring(space + 1).trim();
                if (ref.startsWith("refs/tags/") || ref.startsWith("refs/notes/")) {
                    count++;
                    countedFromPacked = true;
                }
            }
        }
        if (!countedFromPacked) {
            count += repository.getRefDatabase().getRefsByPrefix("refs/tags/").size();
            count += repository.getRefDatabase().getRefsByPrefix("refs/notes/").size();
        }
        return count;
    }

    private static String shortError(Throwable e) {
        if (e == null) {
            return "Inspection failed";
        }
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }

    private int countCommitsBetween(Repository repo, RevCommit base, RevCommit tip) {
        try (RevWalk rw = new RevWalk(repo)) {
            rw.markStart(rw.parseCommit(tip));
            rw.markUninteresting(rw.parseCommit(base));
            int count = 0;
            for (Iterator<RevCommit> it = rw.iterator(); it.hasNext(); ) {
                it.next();
                count++;
                if (count > 500) break;
            }
            return count;
        } catch (Exception e) {
            return 1;
        }
    }

    private void populateLfsInspection(Repository repository,
                                       RepoMapping mapping,
                                       String sourceRepoUrl,
                                       String targetRepoUrl,
                                       SyncDiffReport.SyncDiffReportBuilder reportBuilder,
                                       DiffInspectionProgressService.Session progress,
                                       DiffInspectOptions inspect) throws Exception {
        progress.markCurrent(DiffInspectionPipeline.LFS, "Checking LFS mirror status");
        var result = gitLfsSyncService.inspectLfsMirrorForDiff(
                mapping,
                sourceRepoUrl,
                targetRepoUrl,
                repository,
                (phase, detail) -> progress.markCurrent(
                        DiffInspectionPipeline.LFS,
                        ("discover".equals(phase) ? "Discovering LFS pointers" : "Verifying on destination")
                                + " · " + detail));

        int discovered = result.totalDiscovered();
        int synced = result.syncedCount();
        if (!result.destVerified()) {
            int knownDest = PairMirrorSnapshotService.lfsSyncedCount(mapping);
            if (knownDest <= 0 && mapping.getLastMirrorLfsObjects() != null) {
                knownDest = mapping.getLastMirrorLfsObjects();
            }
            synced = knownDest;
            log.debug("LFS dest verify did not complete for pair #{}; keeping last known dest count {}",
                    mapping.getId(), knownDest);
        }
        int pending = Math.max(0, discovered - synced);
        reportBuilder.lfs(LfsSyncSummary.builder()
                .totalDiscovered(discovered)
                .syncedCount(Math.min(discovered, synced))
                .pendingCount(pending)
                .inSync(discovered == 0 || (result.destVerified() && synced >= discovered))
                .destVerified(result.destVerified())
                .build());

        int itemCap = Math.max(1, inspect.maxBranchDetails());
        List<SyncDiffReport.LfsPointerDetail> lfsItems = new ArrayList<>();
        for (var lfsObj : result.objects()) {
            if (lfsItems.size() >= itemCap) {
                break;
            }
            lfsItems.add(SyncDiffReport.LfsPointerDetail.builder()
                    .filePath(lfsObj.filePath() != null ? lfsObj.filePath() : "lfs-blob.bin")
                    .oid(lfsObj.oid())
                    .shortOid(lfsObj.oid().substring(0, Math.min(10, lfsObj.oid().length())))
                    .sizeBytes(lfsObj.size())
                    .formattedSize(formatBytes(lfsObj.size()))
                    .headBranch(lfsObj.headBranch() != null ? lfsObj.headBranch() : "main")
                    .build());
        }
        reportBuilder.lfsItems(lfsItems);
        if (result.destVerified() && pairCatchupLedger != null && mapping.getId() != null
                && result.presentOnDest() != null && !result.presentOnDest().isEmpty()) {
            pairCatchupLedger.recordInspectSuccess(mapping.getId(), null, null, result.presentOnDest());
        }
        progress.markDone(DiffInspectionPipeline.LFS,
                (result.destVerified() ? synced : discovered) + "/" + discovered
                        + (result.destVerified() ? " in sync" : " discovered · dest verify incomplete"));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int z = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format("%.1f %sB", (double) bytes / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    private File getOrCreateBareRepoDir(Long mappingId) {
        Path base = Paths.get(workspaceDir);
        try {
            if (!Files.exists(base)) {
                Files.createDirectories(base);
            }
        } catch (IOException ignored) {}
        return new File(base.toFile(), "pair-" + mappingId + ".git");
    }

    private static String prHeadBaseKey(String head, String base) {
        return (head == null ? "" : head.trim().toLowerCase()) + "\0"
                + (base == null ? "" : base.trim().toLowerCase());
    }

    private Git initOrOpenBareGit(File repoDir, String sourceUrl, String targetUrl) throws Exception {
        Git git;
        if (!repoDir.exists() || !new File(repoDir, "config").exists()) {
            repoDir.mkdirs();
            git = Git.init().setBare(true).setDirectory(repoDir).call();
        } else {
            Repository repository = new RepositoryBuilder().setGitDir(repoDir).build();
            git = new Git(repository);
        }

        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", "source", "url", sourceUrl);
        config.setString("remote", "target", "url", targetUrl);
        config.save();
        return git;
    }

    private CredentialsProvider createCredentialsProvider(String repoUrl, String explicitToken) {
        if (scmProviderFacade != null) {
            return scmProviderFacade.getGitCredentials(repoUrl, explicitToken);
        }
        return null;
    }

    private boolean isSimulationOrTestUrl(String url) {
        if (url == null) return true;
        String lower = url.toLowerCase();
        return lower.contains("example.com") || lower.contains("test.com") ||
               lower.contains("dummy") || lower.contains("localhost");
    }
}
