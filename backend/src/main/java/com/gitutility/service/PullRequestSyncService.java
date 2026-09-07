package com.gitutility.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitutility.model.dto.JobStageProgress;
import com.gitutility.model.dto.PrListPage;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.dto.PullRequestSnapshot;
import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.PairSide;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RepoMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@Slf4j
public class PullRequestSyncService {

    private final PrMappingRepository prMappingRepository;
    private final ScmProviderFacade scmProviderFacade;
    private final RepoMappingRepository repoMappingRepository;
    private final StorageTieringService storageTieringService;
    private final DedupLedgerService dedupLedgerService;
    private final SyncConflictService syncConflictService;
    private final JobExecutionStateService jobExecutionStateService;
    private final JobCancellationService jobCancellationService;
    private final PairDiffSnapshotService pairDiffSnapshotService;
    private final ActionsTriggerSuppressionService actionsTriggerSuppressionService;
    private final ExecutorService prCreateExecutor;

    @Value("${git-utility.git.pr-fork-fetch-batch-size:32}")
    private int prForkFetchBatchSize;

    @Value("${git-utility.git.pr-list-page-size:100}")
    private int prListPageSize;

    @Value("${git-utility.git.pr-skip-existing-heads:true}")
    private boolean prSkipExistingHeads;

    @Value("${git-utility.git.pr-head-prep-progress-interval:1}")
    private int prHeadPrepProgressInterval;

    /**
     * When true (default), fork PRs only cache tip objects (local + optional hidden dest ref).
     * Dest {@code fork-pr-*} branches and GitHub PRs are created on demand via materialize.
     */
    @Value("${git-utility.git.pr-fork-lazy-materialize:true}")
    private boolean prForkLazyMaterialize;

    /** Push tip objects to {@code refs/gitmirror/fork-pr/{n}} on dest so DR works if Hub disk is cold. */
    @Value("${git-utility.git.pr-fork-push-object-refs:true}")
    private boolean prForkPushObjectRefs;

    /** Hidden dest ref namespace — not counted as a branch in sync diffs. */
    public static final String FORK_OBJECT_REF_PREFIX = "refs/gitmirror/fork-pr/";
    public static final String STATE_OBJECTS_CACHED = "objects_cached";

    public static String hiddenForkObjectRef(long sourcePrNumber) {
        return FORK_OBJECT_REF_PREFIX + sourcePrNumber;
    }

    public PullRequestSyncService(
            PrMappingRepository prMappingRepository,
            ScmProviderFacade scmProviderFacade,
            RepoMappingRepository repoMappingRepository,
            StorageTieringService storageTieringService,
            DedupLedgerService dedupLedgerService,
            SyncConflictService syncConflictService,
            JobExecutionStateService jobExecutionStateService,
            JobCancellationService jobCancellationService,
            PairDiffSnapshotService pairDiffSnapshotService,
            ActionsTriggerSuppressionService actionsTriggerSuppressionService,
            @Qualifier("prCreateExecutor") ExecutorService prCreateExecutor) {
        this.prMappingRepository = prMappingRepository;
        this.scmProviderFacade = scmProviderFacade;
        this.repoMappingRepository = repoMappingRepository;
        this.storageTieringService = storageTieringService;
        this.dedupLedgerService = dedupLedgerService;
        this.syncConflictService = syncConflictService;
        this.jobExecutionStateService = jobExecutionStateService;
        this.jobCancellationService = jobCancellationService;
        this.pairDiffSnapshotService = pairDiffSnapshotService;
        this.actionsTriggerSuppressionService = actionsTriggerSuppressionService;
        this.prCreateExecutor = prCreateExecutor;
    }

    /**
     * Initial bulk synchronization of open Pull Requests from source to target.
     * Operates seamlessly across GitHub, GitHub Enterprise, Bitbucket, and GitLab via ScmProviderFacade.
     */
    public int syncOpenPullRequests(Long mappingId, String sourceRepoUrl, String targetRepoUrl) {
        return syncOpenPullRequests(mappingId, sourceRepoUrl, targetRepoUrl, null);
    }

    public int syncOpenPullRequests(Long mappingId, String sourceRepoUrl, String targetRepoUrl, Consumer<String> progress) {
        return syncOpenPullRequests(mappingId, sourceRepoUrl, targetRepoUrl, null, progress);
    }

    public int syncOpenPullRequests(Long mappingId,
                                    String sourceRepoUrl,
                                    String targetRepoUrl,
                                    Long jobId,
                                    Consumer<String> progress) {
        String sourceFullName = scmProviderFacade.parseRepoFullName(sourceRepoUrl);
        String targetFullName = scmProviderFacade.parseRepoFullName(targetRepoUrl);

        if (sourceFullName == null || targetFullName == null) {
            log.debug("Skipping PR sync: could not parse repository names.");
            return 0;
        }

        ScmProviderAdapter sourceAdapter = scmProviderFacade.getAdapterForUrl(sourceRepoUrl);
        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(targetRepoUrl);

        log.info("Checking open Pull Requests to synchronize from {} ({}) to {} ({})",
                sourceFullName, sourceAdapter.getProviderType(), targetFullName, targetAdapter.getProviderType());
        if (progress != null) {
            progress.accept("PR metadata · Fetching open pull requests from " + sourceFullName + "...");
        }

        try {
            RepoMapping mapping = repoMappingRepository.findById(mappingId).orElse(null);
            Long sourceCredId = credentialForUrl(mapping, sourceRepoUrl);
            Long targetCredId = credentialForUrl(mapping, targetRepoUrl);
            File repoDir = resolveRepoDir(mappingId, mapping);
            if (repoDir != null && repoDir.exists()) {
                BareRepoHousekeeping.prepareRepoDirectory(repoDir);
            }

            PrMappingIndex mappingIndex = PrMappingIndex.load(prMappingRepository.findByMappingId(mappingId));
            JobStageProgress stageProgress = jobId != null
                    ? jobExecutionStateService.loadStageProgressByJobId(jobId)
                    : JobStageProgress.empty();

            String cursor = Boolean.TRUE.equals(stageProgress.getPrListComplete())
                    ? null
                    : stageProgress.getPrListCursor();
            boolean hasNext = true;
            int totalSynced = 0;
            int totalListed = 0;
            int knownTotal = -1;
            int pageSize = Math.max(1, Math.min(prListPageSize, 100));

            while (hasNext) {
                checkJobControl(jobId);

                PrListPage page;
                try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(sourceCredId)) {
                    page = sourceAdapter.listOpenPullRequestsPage(sourceFullName, cursor, pageSize);
                }
                if (knownTotal < 0 && page.totalCount() >= 0) {
                    knownTotal = page.totalCount();
                }
                totalListed += page.items().size();

                if (progress != null) {
                    String totalLabel = knownTotal >= 0 ? String.valueOf(knownTotal) : totalListed + "+";
                    progress.accept("PR metadata · Listed " + totalListed + "/" + totalLabel + " open PR(s); "
                            + mappingIndex.mappedSourceNumbers().size() + " already mirrored");
                }

                List<MutablePendingPr> pending = buildPendingFromPage(page.items(), mappingIndex);
                if (repoDir != null && repoDir.exists() && mapping != null && !pending.isEmpty()) {
                    materializePendingHeads(repoDir, mapping, pending, progress, mappingId,
                            sourceFullName, targetFullName, mappingIndex);
                } else if (mapping != null) {
                    for (MutablePendingPr item : pending) {
                        if (item.pr.isFork() && prForkLazyMaterialize) {
                            item.headReady = false;
                            item.objectsCached = true;
                        } else if (item.destHead != null && !item.pr.isFork()
                                && !GitSyncEngine.isTrunkBranch(item.destHead)) {
                            // No local mirror to preflight base — create path may still 422.
                            item.headReady = true;
                            item.baseReady = true;
                        }
                    }
                    persistForkObjectCachedStubs(mappingId, sourceFullName, targetFullName, pending, mappingIndex);
                }

                totalSynced += createPendingPullRequests(
                        mappingId, sourceFullName, targetFullName, targetRepoUrl, jobId,
                        targetAdapter, mappingIndex, pending, progress, targetCredId);

                cursor = page.nextCursor();
                hasNext = page.hasNextPage();
                if (page.items().isEmpty() && !hasNext) {
                    break;
                }

                stageProgress.setPrListCursor(cursor);
                stageProgress.setPrListComplete(!hasNext);
                persistPrListCheckpoint(jobId, stageProgress);
            }

            stageProgress.setPrListCursor(null);
            stageProgress.setPrListComplete(true);
            persistPrListCheckpoint(jobId, stageProgress);

            if (progress != null) {
                String totalLabel = knownTotal >= 0 ? String.valueOf(knownTotal) : String.valueOf(totalListed);
                progress.accept("PR metadata · Finished: " + totalSynced + " newly mirrored of "
                        + totalLabel + " open PR(s) on " + targetFullName);
            }
            int sourceTotal = knownTotal >= 0 ? knownTotal : totalListed;
            int destMapped = 0;
            int objectsCached = 0;
            for (PrMapping pm : prMappingRepository.findByMappingId(mappingId)) {
                if (pm.getTargetPrNumber() != null && pm.getTargetPrNumber() > 0) {
                    destMapped++;
                } else if (STATE_OBJECTS_CACHED.equals(pm.getState()) || Boolean.TRUE.equals(pm.isForkPrHead())) {
                    objectsCached++;
                }
            }
            if (pairDiffSnapshotService != null) {
                pairDiffSnapshotService.updatePrs(mappingId, sourceTotal, destMapped, destMapped);
            }
            log.info("Completed Pull Request sync: {} PR(s) newly replicated ({} open on source, {} dest PRs, {} fork tips cached for DR)",
                    totalSynced, sourceTotal, destMapped, objectsCached);
            if (progress != null && objectsCached > 0) {
                progress.accept("PR metadata · " + objectsCached
                        + " fork PR tip(s) cached for DR (no dest branch/PR until materialized)");
            }
            return totalSynced;
        } catch (JobCancelledException | JobPausedException e) {
            throw e;
        } catch (Exception e) {
            if (progress != null) {
                progress.accept("PR metadata · Completed with notice: " + e.getMessage());
            }
            log.warn("PR sync notice: {}", e.getMessage());
            return 0;
        }
    }

    private List<MutablePendingPr> buildPendingFromPage(List<SyncDiffReport.PrSyncDetail> openPrs,
                                                        PrMappingIndex mappingIndex) {
        List<MutablePendingPr> pending = new ArrayList<>();
        for (SyncDiffReport.PrSyncDetail pr : openPrs) {
            if (mappingIndex.isAlreadyMapped(pr)) {
                continue;
            }
            String headRef = pr.getHeadBranch();
            String baseRef = pr.getBaseBranch();
            if (headRef == null || baseRef == null) {
                continue;
            }
            if (headRef.equalsIgnoreCase(baseRef)) {
                continue;
            }
            pending.add(new MutablePendingPr(pr, replicaHeadBranch(pr.getSourcePrNumber(), headRef, pr.isFork())));
        }
        return pending;
    }

    private void checkJobControl(Long jobId) {
        if (jobId == null || jobCancellationService == null) {
            return;
        }
        if (jobCancellationService.isCancelRequested(jobId)) {
            throw new JobCancelledException(jobId);
        }
        if (jobCancellationService.isPauseRequested(jobId)) {
            throw new JobPausedException(jobId);
        }
    }

    private void persistPrListCheckpoint(Long jobId, JobStageProgress stageProgress) {
        if (jobId == null || jobExecutionStateService == null || stageProgress == null) {
            return;
        }
        jobExecutionStateService.persistProgress(jobId, null, stageProgress);
    }

    private File resolveRepoDir(Long mappingId, RepoMapping mapping) {
        if (mapping != null && storageTieringService != null) {
            return storageTieringService.resolveRepoDirectory(mappingId, mapping.getStorageTier());
        }
        return new File("/tmp/git-utility-mirrors/pair-" + mappingId + ".git");
    }

    private void materializePendingHeads(File repoDir,
                                         RepoMapping mapping,
                                         List<MutablePendingPr> pending,
                                         Consumer<String> progress,
                                         Long mappingId,
                                         String sourceFullName,
                                         String targetFullName,
                                         PrMappingIndex mappingIndex) {
        CredentialsProvider srcCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
        CredentialsProvider targetCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
        List<Long> forkPrNumbers = pending.stream()
                .filter(item -> item.pr.isFork())
                .map(item -> item.pr.getSourcePrNumber())
                .toList();
        List<MutablePendingPr> branchPending = pending.stream()
                .filter(item -> !item.pr.isFork() || !prForkLazyMaterialize)
                .toList();

        BareRepoHousekeeping.prepareRepoDirectory(repoDir);
        java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus =
                new java.util.concurrent.atomic.AtomicReference<>("PR head prep starting");
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pr-head-prep-heartbeat");
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> heartbeat = null;
        if (progress != null) {
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                    () -> progress.accept("PR metadata · " + heartbeatStatus.get() + " · still working..."),
                    30, 30, TimeUnit.SECONDS);
        }
        try (Git git = Git.open(repoDir)) {
            if (!forkPrNumbers.isEmpty()) {
                heartbeatStatus.set("prefetching " + forkPrNumbers.size() + " fork PR head ref(s)");
                if (progress != null) {
                    progress.accept("PR metadata · Prefetching " + forkPrNumbers.size()
                            + " fork PR tip object(s) from source (DR cache; no dest branches)...");
                }
                batchFetchForkPullRefs(git, repoDir, "source", srcCreds, forkPrNumbers, progress, heartbeatStatus);
            }

            if (prForkLazyMaterialize) {
                int[] cacheCounts = cacheForkTipsBatched(git, repoDir, targetCreds, pending, progress, heartbeatStatus);
                int cached = cacheCounts[0];
                int failed = cacheCounts[1];
                if (progress != null) {
                    progress.accept("PR metadata · Fork tip cache: " + cached + " stored for DR"
                            + (failed > 0 ? ", " + failed + " missing tip(s)" : "")
                            + " — skipping dest fork-pr branches and GitHub PRs");
                }
                persistForkObjectCachedStubs(mappingId, sourceFullName, targetFullName, pending, mappingIndex);
            }

            List<String> allDestHeads = uniquePushableDestHeads(branchPending);
            if (prSkipExistingHeads && !allDestHeads.isEmpty()) {
                List<String> missingTracking = destHeadsNeedingPrefetch(git, allDestHeads);
                int warm = allDestHeads.size() - missingTracking.size();
                if (missingTracking.isEmpty()) {
                    heartbeatStatus.set("dest tracking already warm");
                    if (progress != null) {
                        progress.accept("PR metadata · Dest tracking already warm (" + allDestHeads.size()
                                + " same-repo tip(s) from git lane) — skipping dest prefetch");
                    }
                } else {
                    heartbeatStatus.set("prefetching destination heads for skip checks");
                    if (progress != null) {
                        if (warm > 0) {
                            progress.accept("PR metadata · Prefetching " + missingTracking.size()
                                    + " dest head tip(s) missing locally (" + warm + " already on dest tracking)...");
                        } else {
                            progress.accept("PR metadata · Prefetching destination head tips for same-repo PRs...");
                        }
                    }
                    prefetchTargetHeadRefs(git, repoDir, targetCreds, missingTracking, progress, heartbeatStatus);
                }
            }

            List<MutablePendingPr> needsPush = new ArrayList<>();
            int skippedExisting = 0;
            int failed = 0;
            for (MutablePendingPr item : pending) {
                if (item.pr.isFork() && prForkLazyMaterialize) {
                    continue;
                }
                String destHead = item.destHead;
                if (destHead == null || GitSyncEngine.isTrunkBranch(destHead)) {
                    item.headReady = false;
                    failed++;
                    continue;
                }
                if (prSkipExistingHeads && destHeadAlreadyMaterialized(git, "target", destHead)) {
                    item.resolvedHead = destHead;
                    item.headReady = true;
                    skippedExisting++;
                    continue;
                }
                needsPush.add(item);
            }
            int materialized = 0;
            if (!needsPush.isEmpty()) {
                if (progress != null) {
                    progress.accept("PR metadata · " + skippedExisting + " already on dest tracking, "
                            + needsPush.size() + " missing head(s) to push");
                }
                materialized = batchMaterializeMissingHeads(
                        git, repoDir, srcCreds, targetCreds, mapping.getRepoBUrl(),
                        needsPush, progress, heartbeatStatus);
                failed += needsPush.size() - materialized;
            }
            if (progress != null && !branchPending.isEmpty()) {
                progress.accept("PR metadata · Same-repo head prep done: " + materialized + " pushed, "
                        + skippedExisting + " already on target, " + failed + " not ready of " + branchPending.size());
            }

            ensurePendingBases(git, repoDir, srcCreds, targetCreds, mapping.getRepoBUrl(),
                    pending, progress, heartbeatStatus);
        } catch (Exception e) {
            log.warn("Bulk PR head materialization notice: {}", e.getMessage());
            if (progress != null) {
                progress.accept("PR metadata · Head prep notice: " + e.getMessage());
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            heartbeatScheduler.shutdownNow();
        }
    }

    /**
     * Ensures each create-ready PR's <strong>base</strong> ref exists on dest (tracking + push from
     * source when missing). Without this, GitHub returns {@code 422 base invalid} after we only
     * preflighted the head.
     */
    private void ensurePendingBases(Git git, File repoDir,
                                    CredentialsProvider srcCreds, CredentialsProvider targetCreds,
                                    String pushRepoUrl, List<MutablePendingPr> pending,
                                    Consumer<String> progress,
                                    java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        List<MutablePendingPr> candidates = pending.stream()
                .filter(item -> item.headReady && !(item.pr.isFork() && prForkLazyMaterialize))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        List<String> baseNames = uniqueBaseBranches(candidates);
        if (!baseNames.isEmpty()) {
            List<String> missingTracking = destBranchesNeedingPrefetch(git, baseNames);
            if (!missingTracking.isEmpty()) {
                if (heartbeatStatus != null) {
                    heartbeatStatus.set("prefetching " + missingTracking.size() + " dest base tip(s)");
                }
                if (progress != null) {
                    progress.accept("PR metadata · Prefetching " + missingTracking.size()
                            + " dest base tip(s) before PR create...");
                }
                prefetchTargetHeadRefs(git, repoDir, targetCreds, missingTracking, progress, heartbeatStatus);
            }
        }

        List<MutablePendingPr> needPush = new ArrayList<>();
        int already = 0;
        for (MutablePendingPr item : candidates) {
            String base = RefOriginService.branchName(item.pr.getBaseBranch());
            if (base == null || base.isBlank()) {
                item.baseReady = false;
                continue;
            }
            if (destBranchAlreadyExists(git, "target", base)) {
                item.baseReady = true;
                already++;
            } else {
                needPush.add(item);
            }
        }

        int pushed = 0;
        if (!needPush.isEmpty()) {
            if (progress != null) {
                progress.accept("PR metadata · " + already + " base(s) already on dest, "
                        + needPush.size() + " missing — pushing from source...");
            }
            pushed = batchMaterializeMissingBases(git, repoDir, srcCreds, targetCreds, pushRepoUrl,
                    needPush, progress, heartbeatStatus);
        }

        int missing = 0;
        for (MutablePendingPr item : candidates) {
            if (!item.baseReady) {
                missing++;
                log.info("Skipping PR #{} mirror create: base '{}' missing on dest (head '{}') — BASE_REF_MISSING",
                        item.pr.getSourcePrNumber(), item.pr.getBaseBranch(), item.resolvedHead);
            }
        }
        if (progress != null) {
            progress.accept("PR metadata · Base prep done: " + (already + pushed) + " ready, "
                    + missing + " missing of " + candidates.size());
        }
    }

    /**
     * Push missing PR base branches from source onto dest (same ref name). Includes trunks —
     * unlike head materialization, {@code main}/{@code master} bases are valid and required.
     */
    private int batchMaterializeMissingBases(Git git, File repoDir,
                                             CredentialsProvider fetchCreds, CredentialsProvider pushCreds,
                                             String pushRepoUrl, List<MutablePendingPr> needBase,
                                             Consumer<String> progress,
                                             java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        List<RefSpec> fetchSpecs = new ArrayList<>();
        Set<String> seenFetch = new HashSet<>();
        for (MutablePendingPr item : needBase) {
            String base = RefOriginService.branchName(item.pr.getBaseBranch());
            if (base == null || base.isBlank() || !seenFetch.add(base)) {
                continue;
            }
            try {
                if (git.getRepository().exactRef("refs/heads/" + base) == null
                        && git.getRepository().exactRef("refs/remotes/target/" + base) == null) {
                    fetchSpecs.add(new RefSpec("+refs/heads/" + base + ":refs/heads/" + base));
                }
            } catch (Exception ignored) {
            }
        }
        if (!fetchSpecs.isEmpty()) {
            if (progress != null) {
                progress.accept("PR metadata · Fetching " + fetchSpecs.size() + " missing source base(s)...");
            }
            fetchRefSpecBatches(git, repoDir, "source", fetchCreds, fetchSpecs,
                    "source base fetch", "Source base fetch", progress, heartbeatStatus);
        }

        Map<String, List<MutablePendingPr>> byDestRef = new LinkedHashMap<>();
        List<RefSpec> pushSpecs = new ArrayList<>();
        Set<String> seenDest = new HashSet<>();
        for (MutablePendingPr item : needBase) {
            String base = RefOriginService.branchName(item.pr.getBaseBranch());
            if (base == null || base.isBlank()) {
                item.baseReady = false;
                continue;
            }
            try {
                Ref local = git.getRepository().exactRef("refs/heads/" + base);
                if (local == null || local.getObjectId() == null) {
                    item.baseReady = false;
                    continue;
                }
            } catch (Exception e) {
                item.baseReady = false;
                continue;
            }
            String destRef = "refs/heads/" + base;
            byDestRef.computeIfAbsent(destRef, k -> new ArrayList<>()).add(item);
            if (seenDest.add(destRef)) {
                pushSpecs.add(new RefSpec("+" + destRef + ":" + destRef));
            }
        }
        if (pushSpecs.isEmpty()) {
            return 0;
        }

        Map<String, ObjectId> pushed = pushRefSpecBatches(git, repoDir, "target", pushCreds, pushSpecs,
                "missing PR bases", progress, heartbeatStatus);
        int ready = 0;
        Set<String> trackingUpdated = new HashSet<>();
        for (Map.Entry<String, List<MutablePendingPr>> entry : byDestRef.entrySet()) {
            ObjectId sha = pushed.get(entry.getKey());
            boolean ok = sha != null || destBranchAlreadyExists(git, "target",
                    RefOriginService.branchName(entry.getKey()));
            ObjectId realSha = (sha != null && !ObjectId.zeroId().equals(sha)) ? sha : null;
            if (ok && realSha != null && trackingUpdated.add(entry.getKey())) {
                GitSyncEngine.applyPushedRefToDestTracking(git, entry.getKey(), realSha);
                recordDestPushOnLedger(dedupLedgerService, pushRepoUrl, ObjectId.toString(realSha));
            }
            for (MutablePendingPr item : entry.getValue()) {
                if (ok) {
                    item.baseReady = true;
                    ready++;
                } else {
                    item.baseReady = false;
                }
            }
        }
        return ready;
    }

    static List<String> uniqueBaseBranches(List<MutablePendingPr> pending) {
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return pending.stream()
                .map(item -> item.pr != null ? RefOriginService.branchName(item.pr.getBaseBranch()) : null)
                .filter(Objects::nonNull)
                .filter(b -> !b.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Like {@link #destHeadsNeedingPrefetch} but includes trunk bases ({@code main}/{@code master}).
     */
    static List<String> destBranchesNeedingPrefetch(Git git, List<String> branches) {
        if (branches == null || branches.isEmpty()) {
            return List.of();
        }
        List<String> unique = branches.stream()
                .filter(Objects::nonNull)
                .map(RefOriginService::branchName)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .toList();
        if (git == null) {
            return unique;
        }
        List<String> missing = new ArrayList<>();
        for (String branch : unique) {
            if (!destBranchAlreadyExists(git, "target", branch)) {
                missing.add(branch);
            }
        }
        return missing;
    }

    /**
     * Marks locally present {@code refs/pull/{n}/head} tips as cached and pushes hidden dest
     * object refs in batches (one Git negotiation per batch, not per PR).
     *
     * @return {@code [cached, missing]}
     */
    private int[] cacheForkTipsBatched(Git git, File repoDir, CredentialsProvider targetCreds,
                                       List<MutablePendingPr> pending, Consumer<String> progress,
                                       java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        int cached = 0;
        int failed = 0;
        List<RefSpec> hiddenSpecs = new ArrayList<>();
        for (MutablePendingPr item : pending) {
            if (!item.pr.isFork()) {
                continue;
            }
            item.headReady = false;
            long prNum = item.pr.getSourcePrNumber();
            try {
                Ref pullRef = git.getRepository().exactRef("refs/pull/" + prNum + "/head");
                if (pullRef == null || pullRef.getObjectId() == null) {
                    item.objectsCached = false;
                    failed++;
                    continue;
                }
                item.objectsCached = true;
                cached++;
                if (prForkPushObjectRefs && targetCreds != null) {
                    hiddenSpecs.add(new RefSpec("+" + pullRef.getName() + ":" + hiddenForkObjectRef(prNum)));
                }
            } catch (Exception e) {
                log.debug("Fork tip cache notice for PR #{}: {}", prNum, e.getMessage());
                item.objectsCached = false;
                failed++;
            }
        }
        if (!hiddenSpecs.isEmpty()) {
            if (heartbeatStatus != null) {
                heartbeatStatus.set("pushing " + hiddenSpecs.size() + " hidden fork object ref(s)");
            }
            if (progress != null) {
                progress.accept("PR metadata · Pushing " + hiddenSpecs.size()
                        + " hidden fork object ref(s) to dest (DR cache)...");
            }
            pushRefSpecBatches(git, repoDir, "target", targetCreds, hiddenSpecs,
                    "hidden fork object refs", progress, heartbeatStatus);
        }
        return new int[]{cached, failed};
    }

    /**
     * Ensures {@code refs/pull/{n}/head} is local and optionally pushes tip objects to a hidden
     * dest ref ({@link #hiddenForkObjectRef}) so DR can materialize without source.
     */
    private boolean cacheForkTipObjects(Git git, File repoDir, CredentialsProvider targetCreds, long sourcePrNumber) {
        try {
            Ref pullRef = git.getRepository().exactRef("refs/pull/" + sourcePrNumber + "/head");
            if (pullRef == null || pullRef.getObjectId() == null) {
                return false;
            }
            if (prForkPushObjectRefs && targetCreds != null) {
                String hidden = hiddenForkObjectRef(sourcePrNumber);
                try {
                    git.push()
                            .setRemote("target")
                            .setRefSpecs(new RefSpec("+" + pullRef.getName() + ":" + hidden))
                            .setCredentialsProvider(targetCreds)
                            .setTimeout(120)
                            .call();
                    BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                } catch (Exception pushEx) {
                    log.debug("Hidden fork object ref push notice for PR #{}: {}", sourcePrNumber, pushEx.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("Fork tip cache notice for PR #{}: {}", sourcePrNumber, e.getMessage());
            return false;
        }
    }

    private void persistForkObjectCachedStubs(Long mappingId,
                                              String sourceFullName,
                                              String targetFullName,
                                              List<MutablePendingPr> pending,
                                              PrMappingIndex mappingIndex) {
        if (mappingId == null || pending == null || !prForkLazyMaterialize) {
            return;
        }
        List<PrMapping> toSave = new ArrayList<>();
        for (MutablePendingPr item : pending) {
            if (!item.pr.isFork() || !item.objectsCached) {
                continue;
            }
            if (mappingIndex.isAlreadyMapped(item.pr)) {
                continue;
            }
            SyncDiffReport.PrSyncDetail pr = item.pr;
            toSave.add(PrMapping.builder()
                    .mappingId(mappingId)
                    .sourceRepo(sourceFullName)
                    .targetRepo(targetFullName)
                    .sourcePrNumber(pr.getSourcePrNumber())
                    .targetPrNumber(null)
                    .headBranch(hiddenForkObjectRef(pr.getSourcePrNumber()))
                    .baseBranch(pr.getBaseBranch())
                    .title(pr.getTitle())
                    .state(STATE_OBJECTS_CACHED)
                    .forkPrHead(true)
                    .originSide(PairSide.A.name())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
            mappingIndex.remember(pr.getSourcePrNumber(), pr.getHeadBranch(), pr.getBaseBranch());
        }
        if (!toSave.isEmpty()) {
            prMappingRepository.saveAll(toSave);
        }
    }

    /**
     * DR / operator path: promote a cached fork tip into {@code fork-pr-{n}} + dest GitHub PR.
     */
    public Long materializeForkPrForDr(Long mappingId, long sourcePrNumber) {
        RepoMapping mapping = repoMappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        PrMapping stub = prMappingRepository.findByMappingIdAndSourcePrNumber(mappingId, sourcePrNumber)
                .orElseThrow(() -> new IllegalArgumentException("No cached fork PR #" + sourcePrNumber
                        + " for mapping " + mappingId));
        if (stub.getTargetPrNumber() != null && stub.getTargetPrNumber() > 0) {
            return stub.getTargetPrNumber();
        }

        String sourceFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoAUrl());
        String targetFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoBUrl());
        File repoDir = resolveRepoDir(mappingId, mapping);
        if (repoDir == null || !repoDir.exists()) {
            throw new IllegalStateException("Local mirror missing; cannot materialize fork PR #" + sourcePrNumber);
        }

        CredentialsProvider srcCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
        CredentialsProvider targetCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
        String destBranch = replicaHeadBranch(sourcePrNumber, stub.getHeadBranch(), true);
        try (Git git = Git.open(repoDir)) {
            Ref pullRef = git.getRepository().exactRef("refs/pull/" + sourcePrNumber + "/head");
            if (pullRef == null) {
                git.fetch()
                        .setRemote("source")
                        .setRefSpecs(new RefSpec("+refs/pull/" + sourcePrNumber + "/head:refs/pull/" + sourcePrNumber + "/head"))
                        .setCredentialsProvider(srcCreds)
                        .setTimeout(120)
                        .call();
                BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                pullRef = git.getRepository().exactRef("refs/pull/" + sourcePrNumber + "/head");
            }
            if (pullRef == null && stub.getHeadBranch() != null && stub.getHeadBranch().startsWith(FORK_OBJECT_REF_PREFIX)) {
                try {
                    git.fetch()
                            .setRemote("target")
                            .setRefSpecs(new RefSpec("+" + stub.getHeadBranch() + ":refs/pull/" + sourcePrNumber + "/head"))
                            .setCredentialsProvider(targetCreds)
                            .setTimeout(120)
                            .call();
                    BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                    pullRef = git.getRepository().exactRef("refs/pull/" + sourcePrNumber + "/head");
                } catch (Exception e) {
                    log.debug("Could not recover fork tip from dest hidden ref: {}", e.getMessage());
                }
            }
            if (pullRef == null) {
                throw new IllegalStateException("Fork tip objects unavailable for PR #" + sourcePrNumber
                        + " (source down and no local/dest cache)");
            }
            String targetBranchRef = "refs/heads/" + destBranch;
            git.push()
                    .setRemote("target")
                    .setRefSpecs(new RefSpec("+" + pullRef.getName() + ":" + targetBranchRef))
                    .setCredentialsProvider(targetCreds)
                    .setTimeout(120)
                    .call();
            BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
            recordDestPushOnLedger(dedupLedgerService, mapping.getRepoBUrl(), ObjectId.toString(pullRef.getObjectId()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to materialize fork PR #" + sourcePrNumber + ": " + e.getMessage(), e);
        }

        String base = stub.getBaseBranch() != null ? stub.getBaseBranch() : "main";
        if (!tryEnsureBaseOnTarget(repoDir, mapping, base)) {
            throw new IllegalStateException("Cannot materialize fork PR #" + sourcePrNumber
                    + ": base '" + base + "' missing on dest (BASE_REF_MISSING)");
        }

        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(mapping.getRepoBUrl());
        String mirrorBody = PrMirrorSupport.buildMirroredBody(
                sourceFullName, sourcePrNumber, null, null, stub.getTitle());
        Long targetPrNum;
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(mapping.getTargetCredentialId())) {
            targetPrNum = targetAdapter.createPullRequest(
                    targetFullName, stub.getTitle() != null ? stub.getTitle() : ("Fork PR #" + sourcePrNumber),
                    mirrorBody, destBranch, base);
        }
        if (targetPrNum == null) {
            throw new IllegalStateException("Dest PR create returned null for fork PR #" + sourcePrNumber);
        }
        stub.setTargetPrNumber(targetPrNum);
        stub.setHeadBranch(destBranch);
        stub.setState("open");
        stub.setForkPrHead(true);
        stub.setUpdatedAt(Instant.now());
        stub.setLastPushedAt(Instant.now());
        prMappingRepository.save(stub);
        suppressActionsAfterWrite(mapping.getRepoBUrl(), null);
        log.info("Materialized fork PR #{} → dest PR #{} on branch {}", sourcePrNumber, targetPrNum, destBranch);
        return targetPrNum;
    }

    static List<String> uniquePushableDestHeads(List<MutablePendingPr> pending) {
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return pending.stream()
                .map(item -> item.destHead)
                .filter(Objects::nonNull)
                .filter(head -> !GitSyncEngine.isTrunkBranch(head))
                .distinct()
                .toList();
    }

    /**
     * Dest tips that are not already in {@code refs/remotes/target/*} (git-lane inspect/push
     * writes those tracking refs). Prefetch only this subset.
     */
    static List<String> destHeadsNeedingPrefetch(Git git, List<String> destHeads) {
        if (destHeads == null || destHeads.isEmpty()) {
            return List.of();
        }
        List<String> unique = destHeads.stream()
                .filter(Objects::nonNull)
                .filter(head -> !GitSyncEngine.isTrunkBranch(head))
                .distinct()
                .toList();
        if (git == null) {
            return unique;
        }
        List<String> missing = new ArrayList<>();
        for (String head : unique) {
            if (!destHeadAlreadyMaterialized(git, "target", head)) {
                missing.add(head);
            }
        }
        return missing;
    }

    private void prefetchTargetHeadRefs(Git git,
                                        File repoDir,
                                        CredentialsProvider targetCreds,
                                        List<String> destHeads,
                                        Consumer<String> progress,
                                        java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        if (git == null || destHeads == null || destHeads.isEmpty()) {
            return;
        }
        List<RefSpec> specs = new ArrayList<>();
        for (String destHead : destHeads) {
            String branch = RefOriginService.branchName(destHead);
            if (branch == null || branch.isBlank()) {
                continue;
            }
            specs.add(new RefSpec("+refs/heads/" + branch + ":refs/remotes/target/" + branch));
        }
        fetchRefSpecBatches(git, repoDir, "target", targetCreds, specs,
                "destination head prefetch", "Destination head prefetch", progress, heartbeatStatus);
    }

    private void batchFetchForkPullRefs(Git git,
                                        File repoDir,
                                        String fetchRemote,
                                        CredentialsProvider fetchCreds,
                                        List<Long> prNumbers,
                                        Consumer<String> progress,
                                        java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        if (git == null || prNumbers == null || prNumbers.isEmpty()) {
            return;
        }
        List<RefSpec> specs = new ArrayList<>();
        for (Long prNumber : prNumbers) {
            specs.add(new RefSpec("+refs/pull/" + prNumber + "/head:refs/pull/" + prNumber + "/head"));
        }
        fetchRefSpecBatches(git, repoDir, fetchRemote, fetchCreds, specs,
                "fork PR ref prefetch", "Fork head prefetch", progress, heartbeatStatus);
    }

    private void fetchRefSpecBatches(Git git, File repoDir, String remote, CredentialsProvider creds,
                                     List<RefSpec> specs, String heartbeatPrefix, String progressPrefix,
                                     Consumer<String> progress,
                                     java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        if (specs == null || specs.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, prForkFetchBatchSize);
        int totalBatches = (specs.size() + batchSize - 1) / batchSize;
        for (int offset = 0, batchNo = 0; offset < specs.size(); offset += batchSize) {
            batchNo++;
            List<RefSpec> batch = specs.subList(offset, Math.min(offset + batchSize, specs.size()));
            if (heartbeatStatus != null) {
                heartbeatStatus.set(heartbeatPrefix + " batch " + batchNo + "/" + totalBatches
                        + " (" + batch.size() + " ref(s))");
            }
            if (progress != null && (batchNo == 1 || batchNo == totalBatches || batchNo % 5 == 0)) {
                progress.accept("PR metadata · " + progressPrefix + " " + batchNo + "/" + totalBatches
                        + " (" + Math.min(offset + batch.size(), specs.size()) + "/" + specs.size() + " tips)");
            }
            try {
                git.fetch()
                        .setRemote(remote)
                        .setRefSpecs(batch)
                        .setCredentialsProvider(creds)
                        .setTimeout(120)
                        .call();
                BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
            } catch (Exception e) {
                log.debug("Batch {} notice ({} refs): {}", heartbeatPrefix, batch.size(), e.getMessage());
            }
        }
    }

    /**
     * Fetch missing source tips then push remaining dest heads in batches (one Git
     * negotiation per batch instead of one {@code git.push()} per PR).
     *
     * @return number of PRs whose dest head was pushed or already succeeded
     */
    private int batchMaterializeMissingHeads(Git git, File repoDir,
                                             CredentialsProvider fetchCreds, CredentialsProvider pushCreds,
                                             String pushRepoUrl, List<MutablePendingPr> needsPush,
                                             Consumer<String> progress,
                                             java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        batchFetchMissingSourceHeads(git, repoDir, fetchCreds, needsPush, progress, heartbeatStatus);

        Map<String, List<MutablePendingPr>> byDestRef = new LinkedHashMap<>();
        List<RefSpec> pushSpecs = new ArrayList<>();
        Set<String> seenDest = new HashSet<>();
        int notReady = 0;
        for (MutablePendingPr item : needsPush) {
            String sourceRef = resolveLocalHeadSource(git, item);
            if (sourceRef == null) {
                item.headReady = false;
                notReady++;
                continue;
            }
            String destBranch = item.destHead;
            String destRef = destBranch.startsWith("refs/") ? destBranch : "refs/heads/" + destBranch;
            byDestRef.computeIfAbsent(destRef, k -> new ArrayList<>()).add(item);
            if (seenDest.add(destRef)) {
                pushSpecs.add(new RefSpec("+" + sourceRef + ":" + destRef));
            }
        }
        if (pushSpecs.isEmpty()) {
            return 0;
        }

        Map<String, ObjectId> pushed = pushRefSpecBatches(git, repoDir, "target", pushCreds, pushSpecs,
                "missing same-repo heads", progress, heartbeatStatus);
        int materialized = 0;
        Set<String> trackingUpdated = new HashSet<>();
        for (Map.Entry<String, List<MutablePendingPr>> entry : byDestRef.entrySet()) {
            ObjectId sha = pushed.get(entry.getKey());
            boolean ok = sha != null || destHeadAlreadyMaterialized(git, "target",
                    RefOriginService.branchName(entry.getKey()));
            ObjectId realSha = (sha != null && !ObjectId.zeroId().equals(sha)) ? sha : null;
            if (ok && realSha != null && trackingUpdated.add(entry.getKey())) {
                GitSyncEngine.applyPushedRefToDestTracking(git, entry.getKey(), realSha);
                recordDestPushOnLedger(dedupLedgerService, pushRepoUrl, ObjectId.toString(realSha));
            }
            for (MutablePendingPr item : entry.getValue()) {
                if (ok) {
                    item.resolvedHead = item.destHead;
                    item.headReady = true;
                    materialized++;
                } else {
                    item.headReady = false;
                }
            }
        }
        if (notReady > 0) {
            log.debug("Same-repo head batch: {} PR(s) had no local source tip to push", notReady);
        }
        return materialized;
    }

    private void batchFetchMissingSourceHeads(Git git, File repoDir, CredentialsProvider fetchCreds,
                                              List<MutablePendingPr> needsPush, Consumer<String> progress,
                                              java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        List<RefSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MutablePendingPr item : needsPush) {
            if (item.pr.isFork()) {
                String pull = "refs/pull/" + item.pr.getSourcePrNumber() + "/head";
                try {
                    if (git.getRepository().exactRef(pull) == null && seen.add(pull)) {
                        specs.add(new RefSpec("+" + pull + ":" + pull));
                    }
                } catch (Exception ignored) {
                }
                continue;
            }
            String localName = RefOriginService.branchName(item.pr.getHeadBranch());
            if (localName == null || localName.isBlank() || !seen.add(localName)) {
                continue;
            }
            try {
                if (git.getRepository().exactRef("refs/heads/" + localName) == null
                        && git.getRepository().exactRef("refs/remotes/target/" + localName) == null) {
                    specs.add(new RefSpec("+refs/heads/" + localName + ":refs/heads/" + localName));
                }
            } catch (Exception ignored) {
            }
        }
        if (specs.isEmpty()) {
            return;
        }
        if (progress != null) {
            progress.accept("PR metadata · Fetching " + specs.size() + " missing source head(s) before dest push...");
        }
        fetchRefSpecBatches(git, repoDir, "source", fetchCreds, specs,
                "source head fetch", "Source head fetch", progress, heartbeatStatus);
    }

    private String resolveLocalHeadSource(Git git, MutablePendingPr item) {
        try {
            if (item.pr.isFork()) {
                Ref pullRef = git.getRepository().exactRef("refs/pull/" + item.pr.getSourcePrNumber() + "/head");
                return pullRef != null && pullRef.getObjectId() != null ? pullRef.getName() : null;
            }
            String localName = RefOriginService.branchName(item.pr.getHeadBranch());
            if (localName == null || localName.isBlank()) {
                return null;
            }
            Ref localBranchRef = git.getRepository().exactRef("refs/heads/" + localName);
            if (localBranchRef == null) {
                localBranchRef = git.getRepository().exactRef("refs/remotes/target/" + localName);
            }
            return localBranchRef != null && localBranchRef.getObjectId() != null ? localBranchRef.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return dest ref name → pushed SHA for successful remote updates
     */
    private Map<String, ObjectId> pushRefSpecBatches(Git git, File repoDir, String pushRemote,
                                                     CredentialsProvider pushCreds, List<RefSpec> specs,
                                                     String label, Consumer<String> progress,
                                                     java.util.concurrent.atomic.AtomicReference<String> heartbeatStatus) {
        Map<String, ObjectId> succeeded = new LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            return succeeded;
        }
        int batchSize = Math.max(1, prForkFetchBatchSize);
        int totalBatches = (specs.size() + batchSize - 1) / batchSize;
        for (int offset = 0, batchNo = 0; offset < specs.size(); offset += batchSize) {
            batchNo++;
            List<RefSpec> batch = specs.subList(offset, Math.min(offset + batchSize, specs.size()));
            if (heartbeatStatus != null) {
                heartbeatStatus.set("pushing " + label + " batch " + batchNo + "/" + totalBatches
                        + " (" + batch.size() + " ref(s))");
            }
            if (progress != null) {
                progress.accept("PR metadata · Pushing " + label + " " + batchNo + "/" + totalBatches
                        + " (" + Math.min(offset + batch.size(), specs.size()) + "/" + specs.size() + " ref(s))");
            }
            try {
                Iterable<PushResult> pushResults = git.push()
                        .setRemote(pushRemote)
                        .setRefSpecs(batch)
                        .setCredentialsProvider(pushCreds)
                        .setTimeout(120)
                        .call();
                BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                if (pushResults == null) {
                    continue;
                }
                for (PushResult pr : pushResults) {
                    for (RemoteRefUpdate rru : pr.getRemoteUpdates()) {
                        if (!GitSyncEngine.isSuccessfulRemoteUpdate(rru.getStatus())) {
                            log.warn("Push of {} onto '{}' failed: {} ({})",
                                    label, rru.getRemoteName(), rru.getStatus(), rru.getMessage());
                            continue;
                        }
                        ObjectId sha = rru.getNewObjectId();
                        if (sha == null && rru.getSrcRef() != null) {
                            Ref src = git.getRepository().exactRef(rru.getSrcRef());
                            if (src != null) {
                                sha = src.getObjectId();
                            }
                        }
                        if (rru.getRemoteName() != null) {
                            succeeded.put(rru.getRemoteName(), sha != null ? sha : ObjectId.zeroId());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Push of {} batch {}/{} failed: {}", label, batchNo, totalBatches, e.getMessage());
            }
        }
        return succeeded;
    }

    private int createPendingPullRequests(Long mappingId,
                                          String sourceFullName,
                                          String targetFullName,
                                          String targetRepoUrl,
                                          Long jobId,
                                          ScmProviderAdapter targetAdapter,
                                          PrMappingIndex mappingIndex,
                                          List<MutablePendingPr> pending,
                                          Consumer<String> progress,
                                          Long targetCredId) {
        List<MutablePendingPr> ready = pending.stream()
                .filter(item -> item.headReady && item.baseReady && !(item.pr.isFork() && prForkLazyMaterialize))
                .toList();
        if (ready.isEmpty()) {
            long baseBlocked = pending.stream()
                    .filter(item -> item.headReady && !item.baseReady && !(item.pr.isFork() && prForkLazyMaterialize))
                    .count();
            if (baseBlocked > 0 && progress != null) {
                progress.accept("PR metadata · Skipped " + baseBlocked
                        + " PR(s) with head ready but base missing on dest");
            }
            return 0;
        }

        AtomicInteger syncedCount = new AtomicInteger();
        AtomicInteger evaluated = new AtomicInteger();
        List<PrMapping> toSave = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (MutablePendingPr item : ready) {
                tasks.add(() -> {
                    SyncDiffReport.PrSyncDetail pr = item.pr;
                    int done = evaluated.incrementAndGet();
                    if (progress != null && (done == 1 || done % 50 == 0 || done == ready.size())) {
                        progress.accept("PR metadata · Creating mirrors " + done + "/" + ready.size()
                                + " · " + syncedCount.get() + " created");
                    }
                    if (mappingIndex.isAlreadyMapped(pr)) {
                        return null;
                    }
                    String mirrorBody = PrMirrorSupport.buildMirroredBody(
                            sourceFullName,
                            pr.getSourcePrNumber(),
                            pr.getAuthorLogin(),
                            pr.getSourcePrUrl(),
                            pr.getBody());
                    Long targetPrNum;
                    try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(targetCredId)) {
                        targetPrNum = targetAdapter.createPullRequest(
                                targetFullName, pr.getTitle(), mirrorBody, item.resolvedHead, pr.getBaseBranch());
                    }
                    if (targetPrNum != null) {
                        suppressActionsAfterWrite(targetRepoUrl, jobId);
                        toSave.add(PrMapping.builder()
                                .mappingId(mappingId)
                                .sourceRepo(sourceFullName)
                                .targetRepo(targetFullName)
                                .sourcePrNumber(pr.getSourcePrNumber())
                                .targetPrNumber(targetPrNum)
                                .headBranch(item.resolvedHead)
                                .baseBranch(pr.getBaseBranch())
                                .title(pr.getTitle())
                                .state("open")
                                .forkPrHead(pr.isFork() || RefOriginService.isSyntheticForkPrHead(item.resolvedHead))
                                .originSide(PairSide.A.name())
                                .lastPushedTitle(pr.getTitle())
                                .lastPushedBody(mirrorBody)
                                .lastPushedAt(Instant.now())
                                .createdAt(Instant.now())
                                .updatedAt(Instant.now())
                                .build());
                        mappingIndex.remember(pr.getSourcePrNumber(), pr.getHeadBranch(), pr.getBaseBranch());
                        syncedCount.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<?> future : prCreateExecutor.invokeAll(tasks)) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("PR mirror creation interrupted");
        } catch (ExecutionException e) {
            log.warn("PR mirror creation notice: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }

        if (!toSave.isEmpty()) {
            prMappingRepository.saveAll(toSave);
        }
        return syncedCount.get();
    }

    private static final class MutablePendingPr {
        final SyncDiffReport.PrSyncDetail pr;
        final String destHead;
        String resolvedHead;
        boolean headReady;
        /** Dest has the PR base ref (tracking and/or just pushed). Required before GitHub create. */
        boolean baseReady;
        boolean objectsCached;

        MutablePendingPr(SyncDiffReport.PrSyncDetail pr, String destHead) {
            this.pr = pr;
            this.destHead = destHead;
            this.resolvedHead = destHead;
        }
    }

    private static final class PrMappingIndex {
        private final Set<Long> mappedSourceNumbers = new HashSet<>();
        private final Set<String> mappedHeadBaseKeys = new HashSet<>();

        static PrMappingIndex load(List<PrMapping> mappings) {
            PrMappingIndex index = new PrMappingIndex();
            if (mappings == null) {
                return index;
            }
            for (PrMapping pm : mappings) {
                if (pm.getSourcePrNumber() != null) {
                    index.mappedSourceNumbers.add(pm.getSourcePrNumber());
                }
                if (pm.getHeadBranch() != null && pm.getBaseBranch() != null) {
                    index.mappedHeadBaseKeys.add(headBaseKey(pm.getHeadBranch(), pm.getBaseBranch()));
                }
            }
            return index;
        }

        Set<Long> mappedSourceNumbers() {
            return mappedSourceNumbers;
        }

        boolean isAlreadyMapped(SyncDiffReport.PrSyncDetail pr) {
            if (pr == null || pr.getSourcePrNumber() == null) {
                return true;
            }
            if (mappedSourceNumbers.contains(pr.getSourcePrNumber())) {
                return true;
            }
            if (pr.getHeadBranch() != null && pr.getBaseBranch() != null && !pr.isFork()
                    && !GitSyncEngine.isTrunkBranch(pr.getHeadBranch())) {
                return mappedHeadBaseKeys.contains(headBaseKey(pr.getHeadBranch(), pr.getBaseBranch()));
            }
            return false;
        }

        void remember(Long sourcePrNumber, String headBranch, String baseBranch) {
            if (sourcePrNumber != null) {
                mappedSourceNumbers.add(sourcePrNumber);
            }
            if (headBranch != null && baseBranch != null) {
                mappedHeadBaseKeys.add(headBaseKey(headBranch, baseBranch));
            }
        }

        private static String headBaseKey(String head, String base) {
            return (head == null ? "" : head.trim().toLowerCase()) + "\0"
                    + (base == null ? "" : base.trim().toLowerCase());
        }
    }

    /**
     * Fork PR heads are not dest refs. Materialize them on {@code fork-pr-{n}} so a fork whose
     * branch is also named {@code main} cannot force-push onto dest {@code main}.
     * Same-repo PRs keep their real feature branch unless that name is a trunk.
     */
    static String replicaHeadBranch(long sourcePrNumber, String headRef, boolean isFork) {
        String branch = RefOriginService.branchName(headRef);
        if (branch == null || branch.isBlank() || isFork
                || GitSyncEngine.isTrunkBranch(branch)
                || RefOriginService.isSyncConflictBranch(branch)) {
            return "fork-pr-" + sourcePrNumber;
        }
        return branch;
    }

    /**
     * Ensures that the head reference for a PR is present on the push remote.
     * Returns the dest branch name that was actually pushed, or null if skipped/failed.
     */
    private String tryEnsureHeadRefOnTarget(File repoDir, RepoMapping mapping, long sourcePrNumber, String headRef, boolean isFork) {
        if (mapping == null) {
            return null;
        }
        CredentialsProvider srcCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
        CredentialsProvider targetCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
        return tryEnsureHeadRef(repoDir, null, "source", srcCreds, "target", targetCreds,
                mapping.getRepoBUrl(), sourcePrNumber, headRef, isFork);
    }

    /**
     * Ensures {@code baseRef} exists on dest before PR create. Fetches from source and pushes when
     * dest tracking is cold. Returns false when the base still cannot be materialized (caller should
     * skip create — avoids GitHub {@code 422 base invalid}).
     */
    private boolean tryEnsureBaseOnTarget(File repoDir, RepoMapping mapping, String baseRef) {
        if (mapping == null) {
            return false;
        }
        CredentialsProvider srcCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
        CredentialsProvider targetCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
        return tryEnsureBaseRef(repoDir, "source", srcCreds, "target", targetCreds,
                mapping.getRepoBUrl(), baseRef);
    }

    private boolean tryEnsureBaseOnSource(File repoDir, RepoMapping mapping, String baseRef) {
        if (mapping == null) {
            return false;
        }
        CredentialsProvider srcCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
        CredentialsProvider targetCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
        // Dest-originated PR: base must exist on origin (A); tip may only be on B.
        return tryEnsureBaseRef(repoDir, "target", targetCreds, "source", srcCreds,
                mapping.getRepoAUrl(), baseRef);
    }

    private boolean tryEnsureBaseRef(File repoDir, String fetchRemote, CredentialsProvider fetchCreds,
                                     String pushRemote, CredentialsProvider pushCreds, String pushRepoUrl,
                                     String baseRef) {
        String base = RefOriginService.branchName(baseRef);
        if (repoDir == null || !repoDir.exists() || base == null || base.isBlank()) {
            return false;
        }
        BareRepoHousekeeping.prepareRepoDirectory(repoDir);
        try (Git git = Git.open(repoDir)) {
            if (destBranchAlreadyExists(git, pushRemote, base)) {
                return true;
            }
            String heads = "refs/heads/" + base;
            try {
                if (git.getRepository().exactRef(heads) == null) {
                    git.fetch()
                            .setRemote(fetchRemote)
                            .setRefSpecs(new RefSpec("+" + heads + ":" + heads))
                            .setCredentialsProvider(fetchCreds)
                            .setTimeout(120)
                            .call();
                    BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                }
            } catch (Exception fetchEx) {
                log.debug("Base '{}' fetch from {} notice: {}", base, fetchRemote, fetchEx.getMessage());
            }
            Ref local = git.getRepository().exactRef(heads);
            if (local == null || local.getObjectId() == null) {
                return false;
            }
            Iterable<PushResult> results = git.push()
                    .setRemote(pushRemote)
                    .setRefSpecs(new RefSpec("+" + heads + ":" + heads))
                    .setCredentialsProvider(pushCreds)
                    .setTimeout(120)
                    .call();
            BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
            boolean ok = false;
            if (results != null) {
                for (PushResult pr : results) {
                    for (RemoteRefUpdate rru : pr.getRemoteUpdates()) {
                        if (GitSyncEngine.isSuccessfulRemoteUpdate(rru.getStatus())) {
                            ok = true;
                            ObjectId sha = rru.getNewObjectId() != null ? rru.getNewObjectId() : local.getObjectId();
                            if ("target".equals(pushRemote)) {
                                GitSyncEngine.applyPushedRefToDestTracking(git, heads, sha);
                            }
                            recordDestPushOnLedger(dedupLedgerService, pushRepoUrl, ObjectId.toString(sha));
                        }
                    }
                }
            }
            return ok || destBranchAlreadyExists(git, pushRemote, base);
        } catch (Exception e) {
            log.debug("Base '{}' ensure on {} notice: {}", base, pushRemote, e.getMessage());
            return false;
        }
    }

    private String tryEnsureHeadRef(File repoDir, Git existingGit, String fetchRemote, CredentialsProvider fetchCreds,
                                    String pushRemote, CredentialsProvider pushCreds, String pushRepoUrl,
                                    long sourcePrNumber, String headRef, boolean isFork) {
        if (repoDir == null || !repoDir.exists() || headRef == null || headRef.isBlank()) {
            return null;
        }
        String destBranch = replicaHeadBranch(sourcePrNumber, headRef, isFork);
        if (destBranch == null || GitSyncEngine.isTrunkBranch(destBranch)) {
            log.warn("Refusing to materialize PR #{} onto trunk '{}'", sourcePrNumber, destBranch);
            return null;
        }
        String targetBranchRef = destBranch.startsWith("refs/heads/") ? destBranch : "refs/heads/" + destBranch;
        if (existingGit != null) {
            return materializeHeadRef(existingGit, repoDir, fetchRemote, fetchCreds, pushRemote, pushCreds,
                    pushRepoUrl, sourcePrNumber, headRef, isFork, destBranch, targetBranchRef);
        }
        BareRepoHousekeeping.prepareRepoDirectory(repoDir);
        try (Git git = Git.open(repoDir)) {
            return materializeHeadRef(git, repoDir, fetchRemote, fetchCreds, pushRemote, pushCreds,
                    pushRepoUrl, sourcePrNumber, headRef, isFork, destBranch, targetBranchRef);
        } catch (Exception e) {
            log.debug("Head ref preparation notice for PR #{}: {}", sourcePrNumber, e.getMessage());
            return null;
        }
    }

    private String materializeHeadRef(Git git, File repoDir, String fetchRemote, CredentialsProvider fetchCreds,
                                      String pushRemote, CredentialsProvider pushCreds, String pushRepoUrl,
                                      long sourcePrNumber, String headRef, boolean isFork,
                                      String destBranch, String targetBranchRef) {
        try {
            if (prSkipExistingHeads && destHeadAlreadyMaterialized(git, pushRemote, destBranch)) {
                return destBranch;
            }

            if (isFork) {
                Ref pullRef = git.getRepository().exactRef("refs/pull/" + sourcePrNumber + "/head");
                if (pullRef == null) {
                    try {
                        git.fetch()
                                .setRemote(fetchRemote)
                                .setRefSpecs(new RefSpec("+refs/pull/" + sourcePrNumber + "/head:refs/pull/" + sourcePrNumber + "/head"))
                                .setCredentialsProvider(fetchCreds)
                                .setTimeout(30)
                                .call();
                        BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                        pullRef = git.getRepository().exactRef("refs/pull/" + sourcePrNumber + "/head");
                    } catch (Exception fetchEx) {
                        log.debug("Could not fetch fork ref for PR #{}: {}", sourcePrNumber, fetchEx.getMessage());
                    }
                }
                if (pullRef != null && pushRef(git, repoDir, pushRemote, pushCreds, pullRef.getName(), targetBranchRef, sourcePrNumber, destBranch)) {
                    recordDestPushOnLedger(dedupLedgerService, pushRepoUrl, ObjectId.toString(pullRef.getObjectId()));
                    return destBranch;
                }
                return null;
            }

            String localName = RefOriginService.branchName(headRef);
            Ref localBranchRef = git.getRepository().exactRef("refs/heads/" + localName);
            if (localBranchRef == null) {
                Ref targetTracked = git.getRepository().exactRef("refs/remotes/target/" + localName);
                if (targetTracked != null) {
                    localBranchRef = targetTracked;
                }
            }
            if (localBranchRef == null) {
                try {
                    git.fetch()
                            .setRemote(fetchRemote)
                            .setRefSpecs(new RefSpec("+refs/heads/" + localName + ":refs/heads/" + localName))
                            .setCredentialsProvider(fetchCreds)
                            .setTimeout(30)
                            .call();
                    BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                    localBranchRef = git.getRepository().exactRef("refs/heads/" + localName);
                } catch (Exception fetchEx) {
                    log.debug("Could not fetch branch '{}' for PR #{}: {}", headRef, sourcePrNumber, fetchEx.getMessage());
                }
            }
            if (localBranchRef != null && pushRef(git, repoDir, pushRemote, pushCreds, localBranchRef.getName(), targetBranchRef, sourcePrNumber, destBranch)) {
                recordDestPushOnLedger(dedupLedgerService, pushRepoUrl, ObjectId.toString(localBranchRef.getObjectId()));
                return destBranch;
            }
            return null;
        } catch (Exception e) {
            log.debug("Head ref preparation notice for PR #{}: {}", sourcePrNumber, e.getMessage());
            return null;
        }
    }

    private boolean pushRef(Git git, File repoDir, String pushRemote, CredentialsProvider pushCreds,
                            String sourceRef, String targetBranchRef, long sourcePrNumber, String destBranch) {
        try {
            Iterable<PushResult> pushResults = git.push()
                    .setRemote(pushRemote)
                    .setRefSpecs(new RefSpec("+" + sourceRef + ":" + targetBranchRef))
                    .setCredentialsProvider(pushCreds)
                    .setTimeout(60)
                    .call();
            BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
            for (PushResult pr : pushResults) {
                for (RemoteRefUpdate rru : pr.getRemoteUpdates()) {
                    if (!GitSyncEngine.isSuccessfulRemoteUpdate(rru.getStatus())) {
                        log.warn("Push of PR #{} onto '{}' failed: {} ({})", sourcePrNumber, destBranch, rru.getStatus(), rru.getMessage());
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Push of PR #{} onto '{}' failed: {}", sourcePrNumber, destBranch, e.getMessage());
            return false;
        }
    }

    static boolean destHeadAlreadyMaterialized(Git git, String trackingRemote, String destBranch) {
        // Only trust destination tracking refs — a local-only head is not "already on target".
        return destBranchAlreadyExists(git, trackingRemote, destBranch);
    }

    static boolean destBranchAlreadyExists(Git git, String trackingRemote, String destBranch) {
        if (git == null || destBranch == null || destBranch.isBlank()) {
            return false;
        }
        String remote = trackingRemote == null || trackingRemote.isBlank() ? "target" : trackingRemote;
        String branch = RefOriginService.branchName(destBranch);
        try {
            return git.getRepository().exactRef("refs/remotes/" + remote + "/" + branch) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean localHeadBranchExists(Git git, String destBranch) {
        if (git == null || destBranch == null || destBranch.isBlank()) {
            return false;
        }
        String branch = RefOriginService.branchName(destBranch);
        try {
            return git.getRepository().exactRef("refs/heads/" + branch) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Records a dest git push performed for PR head/fork materialization so the inbound
     * GitHub App push webhook is skipped as LOOP_DETECTED_SYSTEM_ECHO.
     */
    static void recordDestPushOnLedger(DedupLedgerService ledger, String destRepoUrl, String sha) {
        if (ledger == null || destRepoUrl == null || sha == null || sha.isBlank()) {
            return;
        }
        ledger.recordSystemPush(destRepoUrl, sha);
    }

    /**
     * Handles real-time pull_request webhook events.
     */
    public void handlePrWebhookEvent(RepoMapping mapping, String action, JsonNode prNode) {
        handlePrWebhookEvent(mapping, action, prNode, null);
    }

    public void handlePrWebhookEvent(RepoMapping mapping, String action, JsonNode prNode, String inboundRepoUrl) {
        if (prNode == null || mapping == null) return;

        String sourceFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoAUrl());
        String targetFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoBUrl());
        if (sourceFullName == null || targetFullName == null) return;

        PairSide inbound = RefOriginService.inboundSide(mapping, inboundRepoUrl);
        long eventPrNumber = prNode.path("number").asLong(prNode.path("id").asLong(0));
        String title = prNode.path("title").asText();
        String body = prNode.path("body").asText(prNode.path("description").asText(""));
        String headRef = prNode.path("head").path("ref").asText(prNode.path("source").path("branch").path("name").asText());
        String baseRef = prNode.path("base").path("ref").asText(prNode.path("destination").path("branch").path("name").asText());
        String inboundFullName = inbound == PairSide.B ? targetFullName : sourceFullName;
        String headRepo = prNode.path("head").path("repo").path("full_name").asText(null);
        boolean isFork = headRepo != null && !headRepo.isBlank() && !headRepo.equalsIgnoreCase(inboundFullName);
        String authorLogin = PrMirrorSupport.authorLoginFromWebhook(prNode);
        String sourcePrUrl = PrMirrorSupport.sourcePrUrlFromWebhook(prNode);

        try {
            if (isOpenAction(action)) {
                replicateOpenedPr(mapping, inbound, eventPrNumber, title, body, headRef, baseRef, isFork,
                        sourceFullName, targetFullName, authorLogin, sourcePrUrl);
            } else if (isCloseAction(action)) {
                closeMappedPullRequest(mapping, inbound, eventPrNumber, sourceFullName, targetFullName);
            } else if (isEditAction(action)) {
                replicateEditedPr(mapping, inbound, eventPrNumber, title, body, sourceFullName, targetFullName,
                        authorLogin, sourcePrUrl);
            }
        } catch (Exception e) {
            log.warn("Error processing PR webhook event: {}", e.getMessage());
        }
    }

    private void replicateOpenedPr(RepoMapping mapping, PairSide inbound, long eventPrNumber,
                                   String title, String body, String headRef, String baseRef, boolean isFork,
                                   String sourceFullName, String targetFullName,
                                   String authorLogin, String sourcePrUrl) {
        Optional<PrMapping> existing = findExistingPr(mapping.getId(), inbound, eventPrNumber, headRef, baseRef);
        if (existing.isPresent()) {
            return;
        }
        if (RefOriginService.isSyncConflictBranch(headRef) || RefOriginService.isSyntheticForkPrHead(headRef)) {
            log.info("Skipping reverse-sync of hub-managed PR head '{}' on mapping {}", headRef, mapping.getId());
            return;
        }

        File repoDir = storageTieringService != null
                ? storageTieringService.resolveRepoDirectory(mapping.getId(), mapping.getStorageTier())
                : new File("/tmp/git-utility-mirrors/pair-" + mapping.getId() + ".git");

        if (inbound == PairSide.B) {
            CredentialsProvider bCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
            CredentialsProvider aCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
            String destHead = replicaHeadBranch(eventPrNumber, headRef, isFork);
            if (repoDir != null && repoDir.exists()) {
                destHead = tryEnsureHeadRef(repoDir, null, "target", bCreds, "source", aCreds,
                        mapping.getRepoAUrl(), eventPrNumber, headRef, isFork);
            }
            if (destHead == null) {
                log.warn("Skipping dest-originated PR #{} replication: head could not be materialized without clobbering origin", eventPrNumber);
                return;
            }
            if (repoDir != null && repoDir.exists() && !tryEnsureBaseOnSource(repoDir, mapping, baseRef)) {
                log.info("Skipping dest-originated PR #{} replication: base '{}' missing on origin (head '{}') — BASE_REF_MISSING",
                        eventPrNumber, baseRef, destHead);
                return;
            }
            ScmProviderAdapter sourceAdapter = scmProviderFacade.getAdapterForUrl(mapping.getRepoAUrl());
            String mirrorBody = PrMirrorSupport.buildMirroredBody(
                    targetFullName, eventPrNumber, authorLogin, sourcePrUrl, body);
            Long aPrNum;
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(mapping.getSourceCredentialId())) {
                aPrNum = sourceAdapter.createPullRequest(sourceFullName, title, mirrorBody, destHead, baseRef);
            }
            if (aPrNum != null) {
                suppressActionsAfterWrite(mapping.getRepoAUrl(), null);
                prMappingRepository.save(PrMapping.builder()
                        .mappingId(mapping.getId())
                        .sourceRepo(sourceFullName)
                        .targetRepo(targetFullName)
                        .sourcePrNumber(aPrNum)
                        .targetPrNumber(eventPrNumber)
                        .headBranch(destHead)
                        .baseBranch(baseRef)
                        .title(title)
                        .state("open")
                        .forkPrHead(isFork || RefOriginService.isSyntheticForkPrHead(destHead))
                        .originSide(PairSide.B.name())
                        .lastPushedTitle(title)
                        .lastPushedBody(mirrorBody)
                        .lastPushedAt(Instant.now())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
                log.info("Real-time webhook replicated dest-originated PR #{} → source #{} on {}",
                        eventPrNumber, aPrNum, sourceFullName);
            }
            return;
        }

        String destHead = replicaHeadBranch(eventPrNumber, headRef, isFork);
        if (isFork && prForkLazyMaterialize) {
            boolean cached = false;
            if (repoDir != null && repoDir.exists()) {
                try (Git git = Git.open(repoDir)) {
                    CredentialsProvider srcCreds = gitCreds(mapping, mapping.getRepoAUrl(), mapping.getTokenA());
                    CredentialsProvider targetCreds = gitCreds(mapping, mapping.getRepoBUrl(), mapping.getTokenB());
                    try {
                        git.fetch()
                                .setRemote("source")
                                .setRefSpecs(new RefSpec("+refs/pull/" + eventPrNumber + "/head:refs/pull/" + eventPrNumber + "/head"))
                                .setCredentialsProvider(srcCreds)
                                .setTimeout(120)
                                .call();
                        BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                    } catch (Exception fetchEx) {
                        log.debug("Webhook fork tip fetch notice for PR #{}: {}", eventPrNumber, fetchEx.getMessage());
                    }
                    cached = cacheForkTipObjects(git, repoDir, targetCreds, eventPrNumber);
                } catch (Exception e) {
                    log.debug("Webhook fork tip cache notice for PR #{}: {}", eventPrNumber, e.getMessage());
                }
            }
            if (cached || repoDir == null || !repoDir.exists()) {
                // Persist stub even if local cache failed when repo missing — listing still tracks the fork PR.
                prMappingRepository.save(PrMapping.builder()
                        .mappingId(mapping.getId())
                        .sourceRepo(sourceFullName)
                        .targetRepo(targetFullName)
                        .sourcePrNumber(eventPrNumber)
                        .targetPrNumber(null)
                        .headBranch(hiddenForkObjectRef(eventPrNumber))
                        .baseBranch(baseRef)
                        .title(title)
                        .state(STATE_OBJECTS_CACHED)
                        .forkPrHead(true)
                        .originSide(PairSide.A.name())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
                log.info("Cached fork PR #{} tip for DR on mapping {} (no dest branch/PR)", eventPrNumber, mapping.getId());
            } else {
                log.warn("Skipping origin fork PR #{}: tip objects could not be cached", eventPrNumber);
            }
            return;
        }
        if (repoDir != null && repoDir.exists()) {
            destHead = tryEnsureHeadRefOnTarget(repoDir, mapping, eventPrNumber, headRef, isFork);
        }
        if (destHead == null && (isFork || GitSyncEngine.isTrunkBranch(headRef))) {
            log.warn("Skipping origin PR #{} replication: fork/trunk head cannot be materialized onto dest", eventPrNumber);
            return;
        }
        if (destHead == null) {
            destHead = replicaHeadBranch(eventPrNumber, headRef, isFork);
        }
        if (repoDir != null && repoDir.exists() && !tryEnsureBaseOnTarget(repoDir, mapping, baseRef)) {
            log.info("Skipping origin PR #{} replication: base '{}' missing on dest (head '{}') — BASE_REF_MISSING",
                    eventPrNumber, baseRef, destHead);
            return;
        }
        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(mapping.getRepoBUrl());
        String mirrorBody = PrMirrorSupport.buildMirroredBody(
                sourceFullName, eventPrNumber, authorLogin, sourcePrUrl, body);
        Long targetPrNum;
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(mapping.getTargetCredentialId())) {
            targetPrNum = targetAdapter.createPullRequest(targetFullName, title, mirrorBody, destHead, baseRef);
        }
        if (targetPrNum != null) {
            suppressActionsAfterWrite(mapping.getRepoBUrl(), null);
            prMappingRepository.save(PrMapping.builder()
                    .mappingId(mapping.getId())
                    .sourceRepo(sourceFullName)
                    .targetRepo(targetFullName)
                    .sourcePrNumber(eventPrNumber)
                    .targetPrNumber(targetPrNum)
                    .headBranch(destHead)
                    .baseBranch(baseRef)
                    .title(title)
                    .state("open")
                    .forkPrHead(isFork || RefOriginService.isSyntheticForkPrHead(destHead))
                    .originSide(PairSide.A.name())
                    .lastPushedTitle(title)
                    .lastPushedBody(mirrorBody)
                    .lastPushedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
            log.info("Real-time webhook replicated: PR #{} created on target {}", targetPrNum, targetFullName);
        }
    }

    private void replicateEditedPr(RepoMapping mapping, PairSide inbound, long eventPrNumber,
                                   String title, String body, String sourceFullName, String targetFullName,
                                   String authorLogin, String sourcePrUrl) {
        PrMapping pm = findExistingPr(mapping.getId(), inbound, eventPrNumber, null, null).orElse(null);
        if (pm == null) {
            return;
        }
        PairSide origin = PairSide.fromString(pm.getOriginSide());
        if (origin == null) {
            origin = PairSide.A;
        }
        if (inbound != origin) {
            log.info("Replica PR edit on mapping {} PR #{} — origin {} is unchanged (no reverse metadata write)",
                    mapping.getId(), eventPrNumber, origin);
            return;
        }
        String attrRepo = origin == PairSide.A ? sourceFullName : targetFullName;
        String mirrorBody = PrMirrorSupport.buildMirroredBody(
                attrRepo, eventPrNumber, authorLogin, sourcePrUrl, body);
        Long replicaPrNum = origin == PairSide.A ? pm.getTargetPrNumber() : pm.getSourcePrNumber();
        String replicaFullName = origin == PairSide.A ? targetFullName : sourceFullName;
        String replicaUrl = origin == PairSide.A ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        if (replicaPrNum == null || replicaPrNum <= 0 || replicaFullName == null) {
            return;
        }
        long replicaPr = replicaPrNum;
        ScmProviderAdapter replicaAdapter = scmProviderFacade.getAdapterForUrl(replicaUrl);
        PullRequestSnapshot replica = replicaAdapter.getPullRequest(replicaFullName, replicaPr);
        if (replica != null && !replicaMatchesLastPush(pm, replica)) {
            log.warn("PR metadata CAS miss on mapping {} replica PR #{} — origin title '{}' vs replica '{}'",
                    mapping.getId(), replicaPr, title, replica.getTitle());
            if (syncConflictService != null) {
                syncConflictService.recordMetadataConflict(
                        mapping.getId(), null, "pr:" + eventPrNumber, replicaFullName,
                        title, replica.getTitle(),
                        "Origin edited title/body but replica was also edited since last push; replica not overwritten."
                );
            }
            return;
        }
        boolean updated = replicaAdapter.updatePullRequest(replicaFullName, replicaPr, title, mirrorBody);
        if (updated) {
            rememberLastPush(pm, title, mirrorBody);
            prMappingRepository.save(pm);
            log.info("Replicated origin PR edit #{} → replica #{} on {}", eventPrNumber, replicaPr, replicaFullName);
        }
    }

    static boolean replicaMatchesLastPush(PrMapping pm, PullRequestSnapshot replica) {
        if (pm == null || replica == null) {
            return true;
        }
        if (pm.getLastPushedTitle() == null && pm.getLastPushedBody() == null) {
            return true;
        }
        return Objects.equals(nullToEmpty(pm.getLastPushedTitle()), nullToEmpty(replica.getTitle()))
                && Objects.equals(nullToEmpty(pm.getLastPushedBody()), nullToEmpty(replica.getBody()));
    }

    static void rememberLastPush(PrMapping pm, String title, String body) {
        if (pm == null) {
            return;
        }
        pm.setLastPushedTitle(title);
        pm.setLastPushedBody(body);
        pm.setLastPushedAt(Instant.now());
        pm.setTitle(title);
        pm.setUpdatedAt(Instant.now());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void closeMappedPullRequest(RepoMapping mapping, PairSide inbound, long eventPrNumber,
                                        String sourceFullName, String targetFullName) {
        PrMapping pm = findExistingPr(mapping.getId(), inbound, eventPrNumber, null, null).orElse(null);
        if (pm == null) {
            return;
        }
        PairSide origin = PairSide.fromString(pm.getOriginSide());
        if (origin == null) {
            origin = PairSide.A;
        }
        boolean inboundIsOrigin = inbound == origin;
        if (inboundIsOrigin) {
            if (origin == PairSide.A && pm.getTargetPrNumber() != null) {
                scmProviderFacade.getAdapterForUrl(mapping.getRepoBUrl())
                        .closePullRequest(targetFullName, pm.getTargetPrNumber());
                if (pm.isForkPrHead() && pm.getHeadBranch() != null
                        && !pm.getHeadBranch().startsWith(FORK_OBJECT_REF_PREFIX)
                        && !pm.getHeadBranch().startsWith("refs/")) {
                    deleteReplicaHead(mapping, mapping.getRepoBUrl(), mapping.getTokenB(), "target", pm.getHeadBranch());
                }
            } else if (origin == PairSide.B && pm.getSourcePrNumber() != null) {
                scmProviderFacade.getAdapterForUrl(mapping.getRepoAUrl())
                        .closePullRequest(sourceFullName, pm.getSourcePrNumber());
                if (pm.isForkPrHead() && pm.getHeadBranch() != null
                        && !pm.getHeadBranch().startsWith(FORK_OBJECT_REF_PREFIX)
                        && !pm.getHeadBranch().startsWith("refs/")) {
                    deleteReplicaHead(mapping, mapping.getRepoAUrl(), mapping.getTokenA(), "source", pm.getHeadBranch());
                }
            }
        } else {
            log.info("Replica PR close on mapping {} PR #{} — origin {} is unchanged",
                    mapping.getId(), eventPrNumber, origin);
        }
        pm.setState("closed");
        pm.setUpdatedAt(Instant.now());
        prMappingRepository.save(pm);
    }

    private Optional<PrMapping> findExistingPr(Long mappingId, PairSide inbound, long eventPrNumber,
                                               String headRef, String baseRef) {
        Optional<PrMapping> existing = inbound == PairSide.B
                ? prMappingRepository.findByMappingIdAndTargetPrNumber(mappingId, eventPrNumber)
                : prMappingRepository.findByMappingIdAndSourcePrNumber(mappingId, eventPrNumber);
        if (existing.isEmpty() && inbound == PairSide.B) {
            existing = prMappingRepository.findByMappingIdAndSourcePrNumber(mappingId, eventPrNumber);
        }
        if (existing.isEmpty() && headRef != null && baseRef != null
                && !GitSyncEngine.isTrunkBranch(headRef)
                && !RefOriginService.isSyntheticForkPrHead(headRef)) {
            existing = prMappingRepository.findByMappingIdAndHeadBranchAndBaseBranch(mappingId, headRef, baseRef);
        }
        return existing;
    }

    private void deleteReplicaHead(RepoMapping mapping, String pushRepoUrl, String token,
                                   String remoteName, String headBranch) {
        if (headBranch == null || headBranch.isBlank() || mapping == null) {
            return;
        }
        File repoDir = storageTieringService != null
                ? storageTieringService.resolveRepoDirectory(mapping.getId(), mapping.getStorageTier())
                : new File("/tmp/git-utility-mirrors/pair-" + mapping.getId() + ".git");
        if (repoDir == null || !repoDir.exists()) {
            return;
        }
        String ref = headBranch.startsWith("refs/heads/") ? headBranch : "refs/heads/" + headBranch;
        if (GitSyncEngine.isTrunkBranch(headBranch)) {
            log.warn("Refusing to delete trunk {} while cleaning replica fork-PR head", ref);
            return;
        }
        BareRepoHousekeeping.prepareRepoDirectory(repoDir);
        try (Git git = Git.open(repoDir)) {
            CredentialsProvider creds = gitCreds(mapping, pushRepoUrl, token);
            git.push()
                    .setRemote(remoteName)
                    .setRefSpecs(new RefSpec(":" + ref))
                    .setCredentialsProvider(creds)
                    .setTimeout(60)
                    .call();
            BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
            if (dedupLedgerService != null) {
                dedupLedgerService.recordSystemRefDelete(pushRepoUrl, ref);
            }
            log.info("Deleted replica fork-PR head {} on {}", ref, pushRepoUrl);
        } catch (Exception e) {
            log.warn("Could not delete replica fork-PR head {} on {}: {}", ref, pushRepoUrl, e.getMessage());
        }
    }

    private CredentialsProvider gitCreds(RepoMapping mapping, String url, String token) {
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credentialForUrl(mapping, url))) {
            return scmProviderFacade.getGitCredentials(url, token);
        }
    }

    private static Long credentialForUrl(RepoMapping mapping, String url) {
        if (mapping == null || url == null) {
            return null;
        }
        if (RepoMappingService.sameRepo(url, mapping.getRepoAUrl())) {
            return mapping.getSourceCredentialId();
        }
        if (RepoMappingService.sameRepo(url, mapping.getRepoBUrl())) {
            return mapping.getTargetCredentialId();
        }
        return null;
    }

    static boolean isOpenAction(String action) {
        return action != null && ("opened".equalsIgnoreCase(action) || "created".equalsIgnoreCase(action));
    }

    static boolean isEditAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toLowerCase();
        return "edited".equals(a) || "update".equals(a) || "updated".equals(a);
    }

    static boolean isCloseAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toLowerCase();
        return "closed".equals(a) || "merged".equals(a) || "declined".equals(a)
                || "rejected".equals(a) || "fulfilled".equals(a);
    }

    private void suppressActionsAfterWrite(String repoUrl, Long jobId) {
        if (actionsTriggerSuppressionService == null || repoUrl == null) {
            return;
        }
        try {
            actionsTriggerSuppressionService.suppressAfterWrite(repoUrl, jobId);
        } catch (Exception e) {
            log.debug("Actions suppression after PR write on {}: {}", repoUrl, e.getMessage());
        }
    }
}
