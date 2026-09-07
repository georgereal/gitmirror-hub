package com.gitutility.service;

import com.gitutility.model.dto.JobStageProgress;
import com.gitutility.model.dto.PermissionCheckReport;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.dto.TestConnectionRequest;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.enums.ConflictKind;
import com.gitutility.model.enums.LogLevel;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.SyncCheckpointStage;
import com.gitutility.model.enums.StorageTier;
import com.gitutility.model.enums.TrunkConflictPolicy;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.repository.SyncJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GitSyncEngine {

    @Value("${git-utility.workspace-dir:/tmp/git-utility-mirrors}")
    private String workspaceDir;

    @Value("${git-utility.git.http-timeout-seconds:600}")
    private int httpTimeoutSeconds;

    @Value("${git-utility.git.push-batch-size:8}")
    private int pushBatchSize;

    @Value("${git-utility.git.push-batch-retries:3}")
    private int pushBatchRetries;

    @Value("${git-utility.git.http-post-buffer-bytes:524288000}")
    private int httpPostBufferBytes;

    private final SyncAuditLogRepository auditLogRepository;
    private final DedupLedgerService dedupLedgerService;
    private final GitLfsSyncService gitLfsSyncService;
    private final StorageTieringService storageTieringService;
    private final RepoMappingRepository repoMappingRepository;
    private final EnterpriseLoggingService enterpriseLoggingService;
    private final com.gitutility.provider.ScmProviderFacade scmProviderFacade;
    private final WebSocketNotificationService webSocketNotificationService;
    private final ProviderRateMeter providerRateMeter;
    private final SyncJobRepository syncJobRepository;
    private final JobCancellationService jobCancellationService;
    private final RefOriginService refOriginService;
    private final SyncConflictService syncConflictService;
    private final SyncJobService syncJobService;
    private final SyncCheckpointService syncCheckpointService;
    private final JobExecutionStateService jobExecutionStateService;
    private final ActionsTriggerSuppressionService actionsTriggerSuppressionService;
    private final ScmCredentialService scmCredentialService;

    private final ThreadLocal<JobStageProgress> activeStageProgress = new ThreadLocal<>();

    public GitSyncEngine(
            SyncAuditLogRepository auditLogRepository,
            DedupLedgerService dedupLedgerService,
            GitLfsSyncService gitLfsSyncService,
            StorageTieringService storageTieringService,
            RepoMappingRepository repoMappingRepository,
            EnterpriseLoggingService enterpriseLoggingService,
            com.gitutility.provider.ScmProviderFacade scmProviderFacade,
            WebSocketNotificationService webSocketNotificationService,
            ProviderRateMeter providerRateMeter,
            SyncJobRepository syncJobRepository,
            JobCancellationService jobCancellationService,
            RefOriginService refOriginService,
            @Lazy SyncConflictService syncConflictService,
            SyncJobService syncJobService,
            SyncCheckpointService syncCheckpointService,
            JobExecutionStateService jobExecutionStateService,
            @Lazy ActionsTriggerSuppressionService actionsTriggerSuppressionService,
            @Lazy ScmCredentialService scmCredentialService) {

        this.auditLogRepository = auditLogRepository;
        this.dedupLedgerService = dedupLedgerService;
        this.gitLfsSyncService = gitLfsSyncService;
        this.storageTieringService = storageTieringService;
        this.repoMappingRepository = repoMappingRepository;
        this.enterpriseLoggingService = enterpriseLoggingService;
        this.scmProviderFacade = scmProviderFacade;
        this.webSocketNotificationService = webSocketNotificationService;
        this.providerRateMeter = providerRateMeter;
        this.syncJobRepository = syncJobRepository;
        this.jobCancellationService = jobCancellationService;
        this.refOriginService = refOriginService;
        this.syncConflictService = syncConflictService;
        this.syncJobService = syncJobService;
        this.syncCheckpointService = syncCheckpointService;
        this.jobExecutionStateService = jobExecutionStateService;
        this.actionsTriggerSuppressionService = actionsTriggerSuppressionService;
        this.scmCredentialService = scmCredentialService;

    }

    public static class SyncResult {
        public boolean success;
        public boolean fastPathShortCircuited;
        public boolean conflictIsolated;
        public String isolatedBranch;
        public List<IsolatedRef> isolatedRefs = new ArrayList<>();
        public String message;
        public List<String> updatedRefs = new ArrayList<>();
        public long durationMs;
        public int branchesCount;
        public int sourceBranchesCount;
        public int destBranchesCount;
        public int inSyncBranchesCount;
        public int destOnlyBranchesCount;
        public int pendingBranchesCount;
        public int tagsCount;
        public int sourceTagsCount;
        public int destTagsCount;
        public int lfsObjectsCount;
        public int lfsSyncedCount;
        public int prsSyncedCount;
        public long bytesTransferred;
        public int objectsReceived;
        public long lfsBytes;
        public String sourceAccessMode;
        public boolean sourceFetchedPublicly;
        public String pipelineJson;
        public String rejectedPushRefs;
        public SyncPipelineState pipeline;
    }

    /**
     * Executes the mirror synchronization from source to target.
     */
    public SyncResult executeSync(SyncEventMessage event) throws Exception {
        long startTime = System.currentTimeMillis();
        Long jobId = event.getJobId();
        SyncResult result = new SyncResult();

        logAudit(jobId, LogLevel.INFO, "Starting Git mirror sync for pair: " + event.getPairName() +
                " from " + maskUrl(event.getSourceRepoUrl()) + " to " + maskUrl(event.getTargetRepoUrl()));
        if (actionsTriggerSuppressionService != null) {
            actionsTriggerSuppressionService.beginJob(jobId);
        }
        throwIfStopRequested(jobId);

        StorageTier storageTier = StorageTier.AUTO_LRU;
        RepoMapping mapping = null;
        if (event.getMappingId() != null) {
            mapping = repoMappingRepository.findById(event.getMappingId()).orElse(null);
            if (mapping != null && mapping.getStorageTier() != null) {
                storageTier = mapping.getStorageTier();
            }
            if (mapping != null && scmCredentialService != null) {
                scmCredentialService.ensureBoundIfUnique(mapping);
                scmCredentialService.requireBoundIfGithub(mapping);
                // Refresh message from mapping so queued jobs enqueued before bind still authenticate.
                if (RepoMappingService.sameRepo(event.getSourceRepoUrl(), mapping.getRepoAUrl())) {
                    if (event.getSourceCredentialId() == null) {
                        event.setSourceCredentialId(mapping.getSourceCredentialId());
                    }
                    if (event.getTargetCredentialId() == null) {
                        event.setTargetCredentialId(mapping.getTargetCredentialId());
                    }
                } else {
                    if (event.getSourceCredentialId() == null) {
                        event.setSourceCredentialId(mapping.getTargetCredentialId());
                    }
                    if (event.getTargetCredentialId() == null) {
                        event.setTargetCredentialId(mapping.getSourceCredentialId());
                    }
                }
                assertCredentialCanAccess(event.getSourceCredentialId(), event.getSourceRepoUrl());
                assertCredentialCanAccess(event.getTargetCredentialId(), event.getTargetRepoUrl());
            }
        }

        File repoDir = null;
        if (storageTieringService != null) {
            repoDir = storageTieringService.resolveRepoDirectory(event.getMappingId(), storageTier);
        }
        if (repoDir == null) {
            repoDir = getOrCreateBareRepoDir(event.getMappingId());
        }

        logAudit(jobId, LogLevel.DEBUG, "Local mirror bare repository path (" + storageTier + "): " + repoDir.getAbsolutePath());

        SyncJob jobRecord = jobId != null ? syncJobRepository.findById(jobId).orElse(null) : null;
        boolean resumingJob = !event.isForceSourceFetch() && jobExecutionStateService.shouldResume(jobRecord);
        SyncPipelineState pipeline = resumingJob
                ? jobExecutionStateService.loadPipeline(jobRecord)
                : SyncPipelineState.initial();
        JobStageProgress stageProgress = resumingJob
                ? jobExecutionStateService.loadStageProgress(jobRecord)
                : JobStageProgress.empty();
        String resumeStageId = (resumingJob && jobRecord.getResumeStageId() != null
                && !jobRecord.getResumeStageId().isBlank())
                ? jobRecord.getResumeStageId()
                : SyncPipelineState.FAST_PATH;

        if (resumingJob) {
            log.info("[job-{}] Job-local resume cursor: stage={}", jobId, resumeStageId);
            logAudit(jobId, LogLevel.INFO, "Resuming from saved job stage '" + resumeStageId
                    + "' (local job state — not re-derived from git mirror).");
        }

        activeStageProgress.set(stageProgress);
        if (resumingJob && jobExecutionStateService.isMetadataPhase(resumeStageId)) {
            logAudit(jobId, LogLevel.INFO,
                    "Git mirror phases already completed for this job — continuing PR/release/LFS sync.");
            result.success = true;
            result.durationMs = System.currentTimeMillis() - startTime;
            result.pipeline = pipeline;
            result.pipelineJson = pipeline.toJson();
            result.message = "Git mirror phases already completed for this job — continuing metadata sync.";
            persistPipeline(jobId, pipeline, null);
            broadcastPipeline(jobId, event.getMappingId(), pipeline);
            activeStageProgress.remove();
            if (actionsTriggerSuppressionService != null) {
                actionsTriggerSuppressionService.endJob(jobId);
            }
            return result;
        }

        String sourceLabel = hostPathLabel(event.getSourceRepoUrl());
        String destLabel = hostPathLabel(event.getTargetRepoUrl());
        boolean syncSucceeded = false;
        try (GitWireByteMeter wireMeter = GitWireByteMeter.open();
             Git git = initOrOpenBareGit(repoDir, event.getSourceRepoUrl(), event.getTargetRepoUrl())) {
            BareRepoHousekeeping.prepareRepoDirectory(repoDir);
            normalizeLegacySourceBranchRefs(git);
            CredentialsProvider sourceCreds = createCredentialsProvider(event, event.getSourceRepoUrl(), event.getTokenA());
            CredentialsProvider targetCreds = createCredentialsProvider(event, event.getTargetRepoUrl(), event.getTokenB());

            if (shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.FAST_PATH)) {
            pipeline.markCurrent(SyncPipelineState.FAST_PATH);
            broadcastPipeline(jobId, event.getMappingId(), pipeline);
            String targetCommitSha = event.getAfterSha();
            String branch = event.getBranch();
            boolean fastPathMarked = false;

            if (targetCommitSha != null && !targetCommitSha.isBlank() && !RefOriginService.isDeletedSha(targetCommitSha)
                    && branch != null && !branch.equals("*") && !isSimulationOrTestUrl(event.getSourceRepoUrl())) {
                try {
                    Repository repo = git.getRepository();
                    ObjectId commitId = repo.resolve(targetCommitSha);
                    if (commitId != null) {
                        Ref branchRef = repo.findRef("refs/heads/" + branch);
                        Ref destTipRef = repo.findRef("refs/remotes/target/" + branch);
                        if (branchRef != null && branchRef.getObjectId() != null
                                && destTipRef != null && destTipRef.getObjectId() != null) {
                            try (RevWalk rw = new RevWalk(repo)) {
                                RevCommit localTip = rw.parseCommit(branchRef.getObjectId());
                                RevCommit destTip = rw.parseCommit(destTipRef.getObjectId());
                                RevCommit eventCommit = rw.parseCommit(commitId);
                                boolean onLocal = rw.isMergedInto(eventCommit, localTip);
                                boolean onDest = rw.isMergedInto(eventCommit, destTip);
                                if (onLocal && onDest) {
                                    pipeline.skipRemainingAfterFastPath();
                                    result.success = true;
                                    result.fastPathShortCircuited = true;
                                    result.durationMs = System.currentTimeMillis() - startTime;
                                    result.pipeline = pipeline;
                                    result.pipelineJson = pipeline.toJson();
                                    result.message = String.format(
                                            "Fast-Path Synced: Commit %s is already on local and destination tips of '%s'. No fetch/push required.",
                                            targetCommitSha.substring(0, Math.min(7, targetCommitSha.length())),
                                            branch);
                                    logAudit(jobId, LogLevel.INFO, result.message);
                                    persistPipeline(jobId, pipeline, null);
                                    broadcastPipeline(jobId, event.getMappingId(), pipeline);
                                    return result;
                                }
                                if (onLocal) {
                                    pipeline.markDone(SyncPipelineState.FAST_PATH,
                                            "Local tip has commit; destination tip still behind");
                                    fastPathMarked = true;
                                    logAudit(jobId, LogLevel.INFO,
                                            "Fast-path not taken: commit is on the local mirror of '" + branch
                                                    + "' but destination tip is still behind — continuing sync.");
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Fast-path reachability evaluation note: {}", ex.getMessage());
                }
            }
            if (!fastPathMarked) {
                pipeline.markDone(SyncPipelineState.FAST_PATH, "No fast-path hit");
            }
            } else {
                logAudit(jobId, LogLevel.INFO, "Skipping fast-path check — already completed for this job.");
            }

            boolean isFullMirror = SyncLaneRouter.isFullMirror(event);
            Map<String, String> alreadyPushed = new LinkedHashMap<>(stageProgress.getCompletedPushRefs());
            if (alreadyPushed.isEmpty() && mapping != null) {
                alreadyPushed.putAll(parseCompletedPushRefs(mapping.getCompletedPushRefs()));
            }

            boolean localPacks = localMirrorHasPacks(repoDir);
            boolean localHeads = hasLocalHeadRefs(git);
            boolean skipFetchForFreshJob = !resumingJob && !event.isForceSourceFetch()
                    && shouldSkipSourceFetch(isFullMirror, localPacks, localHeads, alreadyPushed);
            log.info("[job-{}] Resume decision: resumingJob={}, resumeStage={}, ledgerRefs={} -> skipFetchFresh={}",
                    jobId, resumingJob, resumeStageId, alreadyPushed.size(), skipFetchForFreshJob);

            if (shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.VERIFY_DEST)) {
                pipeline.markCurrent(SyncPipelineState.VERIFY_DEST);
                broadcastPipeline(jobId, event.getMappingId(), pipeline);
                verifyDestinationWritable(event, mapping, jobId, pipeline);
            } else {
                logAudit(jobId, LogLevel.INFO, "Skipping destination preflight — already completed for this job.");
            }
            broadcastPipeline(jobId, event.getMappingId(), pipeline);

            // 1. Fetch from Source (Branches, Tags, Git Notes, and PR heads)
            // Public-first: anonymous HTTPS is the primary path; credentials only if public read fails.
            Boolean effectivePublicRead = mapping != null ? mapping.getSourcePublicRead() : null;
            if (mapping != null && mapping.getSourceVisibility() == com.gitutility.model.enums.RepoVisibility.PRIVATE) {
                effectivePublicRead = Boolean.FALSE;
            }
            final Boolean cachedPublicRead = effectivePublicRead;

            // Warm packs alone miss source commits. Cheap ls-remote tip check decides fetch vs skip.
            if (skipFetchForFreshJob && shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.FETCH_SOURCE)) {
                pipeline.markCurrent(SyncPipelineState.FETCH_SOURCE, "Probing source tips (ls-remote)");
                broadcastPipeline(jobId, event.getMappingId(), pipeline);
                try {
                    TipProbeResult probe = probeSourceTips(git, sourceCreds, cachedPublicRead, jobId);
                    if (probe.differ()) {
                        skipFetchForFreshJob = false;
                        logAudit(jobId, LogLevel.INFO,
                                "Source tips changed (" + probe.summary()
                                        + ") — fetching heads/tags/notes only (PR heads stay on the PR lane).");
                    } else {
                        logAudit(jobId, LogLevel.INFO,
                                "Source tips match local mirror (ls-remote) — skipping pack fetch; will compare to destination.");
                    }
                } catch (Exception tipEx) {
                    skipFetchForFreshJob = false;
                    logAudit(jobId, LogLevel.INFO,
                            "Source tip probe failed (" + tipEx.getMessage()
                                    + ") — falling back to source fetch"
                                    + (localPacks && localHeads ? " without PR heads." : "."));
                }
            }

            // Warm mirror: never re-advertise refs/pull/*/head (vscode-scale ≈ million objects).
            // Start fresh / cold clone still fetch PR heads with the full mirror.
            final boolean includePullHeads = event.isForceSourceFetch() || !(localPacks && localHeads);

            if (!shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.FETCH_SOURCE)) {
                logAudit(jobId, LogLevel.INFO, "Skipping source fetch — already completed for this job.");
            } else if (skipFetchForFreshJob) {
                normalizeLegacySourceBranchRefs(git);
                result.sourceFetchedPublicly = Boolean.TRUE.equals(cachedPublicRead);
                result.sourceAccessMode = result.sourceFetchedPublicly ? "PUBLIC" : "AUTHENTICATED";
                pipeline.markSkipped(SyncPipelineState.FETCH_SOURCE,
                        alreadyPushed.isEmpty()
                                ? "Source tips unchanged (ls-remote)"
                                : alreadyPushed.size() + " ref(s) already pushed; local packs on disk");
                persistPipeline(jobId, pipeline, null);
                broadcastPipeline(jobId, event.getMappingId(), pipeline);
                logAudit(jobId, LogLevel.INFO, alreadyPushed.isEmpty()
                        ? "Skipping source pack fetch; tips unchanged — comparing local tips to destination."
                        : "Skipping source fetch; local packs exist and "
                        + alreadyPushed.size() + " ref(s) were already pushed. Resuming remaining push batches.");
            } else {
                pipeline.markCurrent(SyncPipelineState.FETCH_SOURCE);
                broadcastPipeline(jobId, event.getMappingId(), pipeline);
                LiveGitProgressMonitor fetchMonitor = new LiveGitProgressMonitor(
                        jobId, event.getMappingId(), "fetch", "source", sourceLabel,
                        webSocketNotificationService,
                        (phase, msg) -> {
                            logAudit(jobId, LogLevel.INFO, msg);
                            String detail = msg.length() > 96 ? msg.substring(0, 93) + "..." : msg;
                            pipeline.markCurrent(SyncPipelineState.FETCH_SOURCE, detail);
                            broadcastPipeline(jobId, event.getMappingId(), pipeline);
                        },
                        pipeline::toMap,
                        this::trafficSnapshot,
                        providerRateMeter != null ? providerRateMeter::wallElapsedMs : null,
                        () -> touchRunningDuration(jobId)
                );
                fetchMonitor.setCancelCheck(() -> isStopRequested(jobId));
                logAudit(jobId, LogLevel.INFO, "Source fetch · " + sourceLabel + ": Fetching latest refs from source repository"
                        + (includePullHeads ? " (including PR heads)..." : " (heads/tags/notes; PR heads omitted)..."));
                try {
                    boolean usedPublic = runSourceFetchWithHeartbeat(jobId, fetchMonitor, () ->
                            fetchSourcePublicFirst(git, sourceCreds, cachedPublicRead, fetchMonitor, event,
                                    includePullHeads));
                    if (providerRateMeter != null) {
                        providerRateMeter.incrementGitHttpFetch();
                    }
                    result.sourceFetchedPublicly = usedPublic;
                    result.sourceAccessMode = usedPublic ? "PUBLIC" : "AUTHENTICATED";
                    result.objectsReceived = fetchMonitor.getObjectsReceived();
                    persistSourcePublicRead(event.getMappingId(), usedPublic);
                    pipeline.markDone(SyncPipelineState.FETCH_SOURCE, usedPublic ? "Public HTTPS" : "Authenticated");
                    normalizeLegacySourceBranchRefs(git);
                    BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                    logAudit(jobId, LogLevel.INFO, usedPublic
                            ? "Fetched source anonymously (public HTTPS)."
                            : "Fetched source with provider credentials.");
                } catch (Exception e) {
                    rethrowIfStopRequested(jobId, e);
                    if (isSimulationOrTestUrl(event.getSourceRepoUrl())) {
                        pipeline.markSkipped(SyncPipelineState.FETCH_SOURCE, "Simulated source");
                        logAudit(jobId, LogLevel.WARN, "Simulated repository URL detected (" + event.getSourceRepoUrl() + "). Proceeding in demo mirror mode.");
                    } else {
                        pipeline.markFailed(SyncPipelineState.FETCH_SOURCE, e.getMessage());
                        persistPipeline(jobId, pipeline, null);
                        logAudit(jobId, LogLevel.ERROR, "Fetch from source failed: " + e.getMessage());
                        throw e;
                    }
                }
            }

            // 2. Fetch current Target state for Fast-Forward ancestor check (Conflict Safety)
            boolean isTrunkBranch = isTrunkBranch(event.getBranch());
            boolean targetReachable = false;
            if (!shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.INSPECT_DEST)) {
                pipeline.markSkipped(SyncPipelineState.INSPECT_DEST, "Already completed for this job");
                persistPipeline(jobId, pipeline, null);
                broadcastPipeline(jobId, event.getMappingId(), pipeline);
                logAudit(jobId, LogLevel.INFO, "Skipping destination inspection fetch — already completed for this job.");
                targetReachable = true;
            } else {
            pipeline.markCurrent(SyncPipelineState.INSPECT_DEST, "Fetching destination tips");
            persistPipeline(jobId, pipeline, null);
            broadcastPipeline(jobId, event.getMappingId(), pipeline);
            logAudit(jobId, LogLevel.INFO, "Inspect destination · " + destLabel
                    + ": Fetching current branch and tag tips for delta comparison...");
            try {
                if (!isSimulationOrTestUrl(event.getTargetRepoUrl())) {
                    final CredentialsProvider inspectCreds = createCredentialsProvider(
                            event, event.getTargetRepoUrl(), event.getTokenB());
                    LiveGitProgressMonitor inspectMonitor = new LiveGitProgressMonitor(
                            jobId, event.getMappingId(), "fetch", "inspect", destLabel,
                            webSocketNotificationService,
                            (phase, msg) -> {
                                logAudit(jobId, LogLevel.INFO, msg);
                                String detail = msg.length() > 96 ? msg.substring(0, 93) + "..." : msg;
                                pipeline.markCurrent(SyncPipelineState.INSPECT_DEST, detail);
                                broadcastPipeline(jobId, event.getMappingId(), pipeline);
                            },
                            pipeline::toMap,
                            this::trafficSnapshot,
                            providerRateMeter != null ? providerRateMeter::wallElapsedMs : null,
                            () -> touchRunningDuration(jobId)
                    );
                    inspectMonitor.setCancelCheck(() -> isStopRequested(jobId));
                    try {
                        int advertisedHeads = 0;
                        int advertisedTags = 0;
                        Collection<Ref> advertised = git.lsRemote()
                                .setRemote("target")
                                .setHeads(true)
                                .setTags(true)
                                .setCredentialsProvider(inspectCreds)
                                .setTimeout(httpTimeoutSeconds)
                                .call();
                        if (advertised != null) {
                            for (Ref ref : advertised) {
                                if (ref == null || ref.getName() == null || ref.getName().endsWith("^{}")) {
                                    continue;
                                }
                                if (ref.getName().startsWith("refs/heads/")) {
                                    advertisedHeads++;
                                } else if (ref.getName().startsWith("refs/tags/")) {
                                    advertisedTags++;
                                }
                            }
                        }
                        logAudit(jobId, LogLevel.INFO, "Inspect destination · " + destLabel
                                + ": destination advertises " + advertisedHeads + " branch tip(s) and "
                                + advertisedTags + " tag(s); fetching any missing tip objects...");
                        pipeline.markCurrent(SyncPipelineState.INSPECT_DEST,
                                advertisedHeads + " heads / " + advertisedTags + " tags advertised");
                        broadcastPipeline(jobId, event.getMappingId(), pipeline);
                    } catch (Exception advertiseEx) {
                        logAudit(jobId, LogLevel.INFO, "Inspect destination · " + destLabel
                                + ": tip advertisement preview failed (" + advertiseEx.getMessage()
                                + "); continuing with tip fetch...");
                    }
                    runGitOpWithHeartbeat(jobId,
                            () -> {
                                String live = inspectMonitor.lastProgressMessage();
                                if (live != null && !live.isBlank()) {
                                    return live + " · still in progress...";
                                }
                                return "Inspect destination · " + destLabel
                                        + ": still negotiating destination tips (no object transfer yet)...";
                            },
                            () -> {
                                git.fetch()
                                        .setRemote("target")
                                        .setRefSpecs(
                                                new RefSpec("+refs/heads/*:refs/remotes/target/*"),
                                                new RefSpec("+refs/tags/*:refs/remotes/target-tags/*"),
                                                new RefSpec("+refs/notes/*:refs/remotes/target-notes/*")
                                        )
                                        .setCredentialsProvider(inspectCreds)
                                        .setProgressMonitor(inspectMonitor)
                                        .setTimeout(httpTimeoutSeconds)
                                        .call();
                                return null;
                            });
                    targetReachable = true;
                    if (providerRateMeter != null) {
                        providerRateMeter.incrementGitHttpFetch();
                    }
                    pipeline.markDone(SyncPipelineState.INSPECT_DEST);
                    BareRepoHousekeeping.prepareRepoDirectoryAfterPackIo(repoDir);
                    int destHeads = destHeadBranchNames(git).size();
                    logAudit(jobId, LogLevel.INFO, "Destination inspection complete · " + destHeads
                            + " branch head(s) loaded from " + destLabel + ".");
                } else {
                    pipeline.markSkipped(SyncPipelineState.INSPECT_DEST, "Simulated target");
                }
            } catch (Exception e) {
                pipeline.markDone(SyncPipelineState.INSPECT_DEST, "Inspection incomplete; push will proceed");
                logAudit(jobId, LogLevel.DEBUG, "Target inspection fetch returned: " + e.getMessage() + " (Initial target push will proceed)");
            }
            }

            if (targetReachable && mapping != null && refOriginService != null) {
                refOriginService.observeDestinationHeads(
                        mapping,
                        localHeadBranchNames(git),
                        destHeadBranchNames(git),
                        destSideOf(mapping, event)
                );
            }

            // 3. Fast-Forward Check & Non-Destructive Conflict Isolation
            boolean runPush = shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.PUSH_DEST);
            boolean runConflict = runPush
                    || shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.CONFLICT_CHECK);
            List<RefSpec> pushRefSpecs = new ArrayList<>();
            if (runConflict) {
            pipeline.markCurrent(SyncPipelineState.CONFLICT_CHECK, "Scanning refs");
            persistPipeline(jobId, pipeline, null);
            broadcastPipeline(jobId, event.getMappingId(), pipeline);
            ThrottledAuditProgress refProgress = new ThrottledAuditProgress(msg -> {
                logAudit(jobId, LogLevel.INFO, msg);
                String detail = msg.startsWith("Conflict check · ")
                        ? msg.substring("Conflict check · ".length()) : msg;
                if (detail.length() > 96) {
                    detail = detail.substring(0, 93) + "...";
                }
                pipeline.markCurrent(SyncPipelineState.CONFLICT_CHECK, detail);
                broadcastPipeline(jobId, event.getMappingId(), pipeline);
            }, 2_000L);

            if (isFullMirror) {
                int localBranches;
                try {
                    localBranches = BareRepoHousekeeping.listHeadBranchNames(git.getRepository()).size();
                } catch (Exception e) {
                    localBranches = 0;
                }
                int tagCount = 0;
                try {
                    tagCount = BareRepoHousekeeping.countPackedRefsWithPrefix(git.getRepository(), "refs/tags/");
                } catch (Exception ignored) {
                }
                logAudit(jobId, LogLevel.INFO, "Conflict check · comparing " + localBranches
                        + " local branch head(s) and " + tagCount + " tag(s) against destination...");
                pushRefSpecs.addAll(expandFullMirrorRefSpecs(git, alreadyPushed, targetReachable, mapping, event, result, refProgress));
                pushRefSpecs.addAll(pruneSpecsForOriginDeletes(git, mapping, event, targetReachable));
                pipeline.markDone(SyncPipelineState.CONFLICT_CHECK, pushRefSpecs.size() + " refs remaining");
                logAudit(jobId, LogLevel.INFO, "Targeting expanded refs (" + pushRefSpecs.size()
                        + " remaining to push or update). Default branch first, then batched heads/tags.");
            } else {
                String localRefName = event.getRef();
                String branchName = event.getBranch();
                Ref localRef = localRefName != null ? git.getRepository().exactRef(localRefName) : null;
                Ref remoteTargetRef = branchName != null ? git.getRepository().exactRef("refs/remotes/target/" + branchName) : null;
                PairSide sourceSide = sourceSideOf(mapping, event);

                if (shouldOmitHead(mapping, event, localRefName != null ? localRefName : branchName)) {
                    logAudit(jobId, LogLevel.INFO, "Omitting reverse-sync of '" + branchName
                            + "' (synthetic fork-PR head or replica of a ref originated on the other side).");
                    result.updatedRefs.add("Ref " + (localRefName != null ? localRefName : "refs/heads/" + branchName)
                            + " -> SKIPPED (origin/fork policy)");
                } else if (localRef == null) {
                    // Ref does not exist on source (e.g. branch was deleted or merged)
                    boolean propagate = refOriginService == null
                            || mapping == null
                            || refOriginService.shouldPropagateDelete(mapping, sourceSide,
                                    localRefName != null ? localRefName : branchName);
                    if (targetReachable && remoteTargetRef != null && propagate) {
                        logAudit(jobId, LogLevel.INFO, "Source branch '" + branchName + "' was deleted on source; pushing deletion to destination.");
                        pushRefSpecs.add(new RefSpec(":" + (localRefName != null ? localRefName : "refs/heads/" + branchName)));
                        result.updatedRefs.add("Ref " + (localRefName != null ? localRefName : "refs/heads/" + branchName) + " -> DELETED");
                    } else if (!propagate) {
                        logAudit(jobId, LogLevel.INFO, "Not propagating delete of '" + branchName + "' (replica or protected or synthetic dest head).");
                        result.updatedRefs.add("Ref " + (localRefName != null ? localRefName : "refs/heads/" + branchName) + " -> SKIPPED (delete policy)");
                    } else {
                        logAudit(jobId, LogLevel.INFO, "Source ref '" + localRefName + "' is absent on source and target; nothing to push.");
                        result.updatedRefs.add("Ref " + (localRefName != null ? localRefName : "refs/heads/" + branchName) + " -> UP_TO_DATE (Absent)");
                    }
                } else if (targetReachable && isTrunkBranch && remoteTargetRef != null) {
                    RefSpec trunkSpec = resolveTrunkPushSpec(git, localRefName, branchName,
                            localRef.getObjectId(), remoteTargetRef.getObjectId(), event, mapping, result, jobId);
                    if (trunkSpec != null) {
                        pushRefSpecs.add(trunkSpec);
                    }
                } else if (targetReachable && remoteTargetRef != null
                        && localRef.getObjectId().equals(remoteTargetRef.getObjectId())) {
                    logAudit(jobId, LogLevel.INFO, "Branch '" + branchName + "' is already up to date on target.");
                    result.updatedRefs.add("Ref " + localRefName + " -> UP_TO_DATE");
                } else {
                    pushRefSpecs.add(new RefSpec("+" + localRefName + ":" + localRefName));
                }
                pipeline.markDone(SyncPipelineState.CONFLICT_CHECK);
            }
            } else {
                logAudit(jobId, LogLevel.INFO, "Skipping conflict check and push — already completed for this job.");
            }

            // 4. Push to Target (default branch first, then remaining refs in batches)
            if (runPush) {
            pipeline.markCurrent(SyncPipelineState.PUSH_DEST);
            broadcastPipeline(jobId, event.getMappingId(), pipeline);
            logAudit(jobId, LogLevel.INFO, "Destination push · " + destLabel + ": Pushing mirrored refs to target repository...");
            try {
                if (pushRefSpecs.isEmpty()) {
                    pipeline.markDone(SyncPipelineState.PUSH_DEST, "Nothing to push");
                    logAudit(jobId, LogLevel.INFO, "Nothing to push — no refs require an update on the destination.");
                } else if (isSimulationOrTestUrl(event.getTargetRepoUrl())) {
                    pipeline.markSkipped(SyncPipelineState.PUSH_DEST, "Simulated target");
                    logAudit(jobId, LogLevel.WARN, "Simulated target repository URL detected (" + event.getTargetRepoUrl() + "). Completed simulated push.");
                    result.updatedRefs.add("Ref " + (event.getRef() != null ? event.getRef() : "refs/heads/main") + " -> OK (Simulated)");
                } else {
                    LiveGitProgressMonitor pushMonitor = new LiveGitProgressMonitor(
                            jobId, event.getMappingId(), "push", "destination", destLabel,
                            webSocketNotificationService,
                            (phase, msg) -> logAudit(jobId, LogLevel.INFO, msg),
                            pipeline::toMap,
                            this::trafficSnapshot,
                            providerRateMeter != null ? providerRateMeter::wallElapsedMs : null,
                            () -> touchRunningDuration(jobId)
                    );
                    pushMonitor.setCancelCheck(() -> isStopRequested(jobId));
                    List<List<RefSpec>> batches = partitionPushBatches(pushRefSpecs, Math.max(1, pushBatchSize));
                    Map<String, String> newlyPushed = new LinkedHashMap<>(alreadyPushed);
                    int batchIndex = 0;
                    for (List<RefSpec> batch : batches) {
                        throwIfStopRequested(jobId);
                        batchIndex++;
                        String batchDetail = "batch " + batchIndex + "/" + batches.size() + " · " + describeBatch(batch);
                        pipeline.markCurrent(SyncPipelineState.PUSH_DEST, batchDetail);
                        broadcastPipeline(jobId, event.getMappingId(), pipeline);
                        logAudit(jobId, LogLevel.INFO, "Destination push batch " + batchIndex + "/" + batches.size()
                                + " (" + batch.size() + " ref" + (batch.size() == 1 ? "" : "s") + ") → "
                                + describeBatch(batch));
                        PushBatchResult batchResult = pushBatchWithRetries(git, event, batch, pushMonitor, jobId);
                        if (providerRateMeter != null) {
                            providerRateMeter.incrementGitHttpPushBatch();
                        }
                        int okCount = batchResult.successfulRefNames.size() + batchResult.deletedRemoteNames.size();
                        int rejectedInBatch = batchResult.rejectedMessages.size();
                        logAudit(jobId, LogLevel.INFO, "Destination push batch " + batchIndex + "/" + batches.size()
                                + " complete: " + okCount + " ref(s) OK"
                                + (rejectedInBatch > 0 ? ", " + rejectedInBatch + " rejected" : ""));
                        result.updatedRefs.addAll(batchResult.messages);
                        recordSuccessfulRefs(git, batchResult.successfulRefNames, newlyPushed, event.getTargetRepoUrl(),
                                mapping, sourceSideOf(mapping, event));
                        for (String deletedRef : batchResult.deletedRemoteNames) {
                            deleteDestTrackingForRemoteRef(git, deletedRef);
                            if (dedupLedgerService != null) {
                                dedupLedgerService.recordSystemRefDelete(event.getTargetRepoUrl(), deletedRef);
                            }
                        }
                        persistCompletedPushRefs(event.getMappingId(), newlyPushed, stageProgress);
                        if (okCount > 0 && actionsTriggerSuppressionService != null) {
                            int cancelled;
                            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(event.getTargetCredentialId())) {
                                cancelled = actionsTriggerSuppressionService.suppressAfterWrite(
                                        event.getTargetRepoUrl(), jobId);
                            }
                            if (cancelled > 0) {
                                logAudit(jobId, LogLevel.INFO, "Cancelled " + cancelled
                                        + " mirror-triggered Actions run(s) after push batch "
                                        + batchIndex + ".");
                            }
                        }
                        if (batchResult.destinationRejected || batchResult.authFailure) {
                            result.rejectedPushRefs = String.join("\n", batchResult.rejectedMessages);
                            String failReason = batchResult.authFailure
                                    ? "Destination 401/403"
                                    : firstRejectedSummary(batchResult);
                            pipeline.markFailed(SyncPipelineState.PUSH_DEST, failReason);
                            persistPipeline(jobId, pipeline, result.rejectedPushRefs);
                            throw new IllegalStateException(failReason + (isFullMirror
                                    ? "; aborting remaining destination push batches"
                                    : ""));
                        }
                    }
                    pipeline.markDone(SyncPipelineState.PUSH_DEST, batches.size() + " batch(es)");
                }
                if (isFullMirror && event.getMappingId() != null) {
                    syncCheckpointService.persistStage(event.getMappingId(), SyncCheckpointStage.PUSH_DONE);
                }
                persistAndOpenConflicts(result, mapping, event);
            } catch (Exception e) {
                rethrowIfStopRequested(jobId, e);
                if (isSimulationOrTestUrl(event.getTargetRepoUrl())) {
                    pipeline.markSkipped(SyncPipelineState.PUSH_DEST, "Simulated target");
                    logAudit(jobId, LogLevel.WARN, "Simulated target repository URL detected (" + event.getTargetRepoUrl() + "). Completed simulated push.");
                    result.updatedRefs.add("Ref " + (event.getRef() != null ? event.getRef() : "refs/heads/main") + " -> OK (Simulated)");
                } else {
                    String cleanErr = cleanRootCauseMessage(e);
                    if (!SyncPipelineState.FAILED.equals(statusOf(pipeline, SyncPipelineState.PUSH_DEST))) {
                        pipeline.markFailed(SyncPipelineState.PUSH_DEST, cleanErr);
                    }
                    persistPipeline(jobId, pipeline, result.rejectedPushRefs);
                    logAudit(jobId, LogLevel.ERROR, "Push to target failed: " + cleanErr);
                    throw (e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(cleanErr, e));
                }
            }
            }

            persistPipeline(jobId, pipeline, result.rejectedPushRefs);

            result.bytesTransferred = wireMeter.gitWireBytes();

            // 6. Fallback: record the triggering job SHA if no per-ref ledger writes happened
            if (event.getAfterSha() != null && !event.getAfterSha().isBlank()
                    && !RefOriginService.isDeletedSha(event.getAfterSha())) {
                dedupLedgerService.recordSystemPush(event.getTargetRepoUrl(), event.getAfterSha());
            }

            syncSucceeded = true;
            result.success = true;

            int bCount = 0;
            int tCount = 0;
            fillPairRefCounts(git, result);
            if (result.sourceBranchesCount > 0) {
                bCount = result.sourceBranchesCount;
            } else {
                for (String refMsg : result.updatedRefs) {
                    if (refMsg.contains("refs/heads/")) bCount++;
                    else if (refMsg.contains("refs/tags/")) tCount++;
                }
                if (bCount == 0) {
                    try {
                        bCount = BareRepoHousekeeping.listPairRefNames(git.getRepository()).sourceBranchCount();
                    } catch (Exception ignored) {}
                }
            }
            if (result.sourceTagsCount > 0) {
                tCount = result.sourceTagsCount;
            } else if (tCount == 0) {
                try {
                    tCount = BareRepoHousekeeping.listPairRefNames(git.getRepository()).sourceTags().size();
                } catch (Exception ignored) {}
            }
            result.branchesCount = Math.max(result.branchesCount, bCount);
            result.tagsCount = Math.max(result.tagsCount, tCount);

            if (isFullMirror && !isSimulationOrTestUrl(event.getTargetRepoUrl())) {
                persistCompletedPushRefs(event.getMappingId(), Map.of(), stageProgress);
            }

            pipeline.markCurrent(SyncPipelineState.PR_METADATA, "Git mirror complete");
            result.durationMs = System.currentTimeMillis() - startTime;
            result.pipeline = pipeline;
            result.pipelineJson = pipeline.toJson();
            persistPipeline(jobId, pipeline, result.rejectedPushRefs);
            String volume = formatTransferVolume(wireMeter.gitWireBytes(), result.lfsBytes, result.objectsReceived);
            if (result.conflictIsolated) {
                result.message = conflictSummaryMessage(result);
            } else {
                result.message = "Git object mirror finished (" + volume + ", " + result.branchesCount + " branches, "
                        + result.tagsCount + " tags) in " + result.durationMs + "ms"
                        + ("PUBLIC".equals(result.sourceAccessMode) ? " (public source)" : "")
                        + "; PR, release metadata, and LFS may still be running";
            }
            logAudit(jobId, LogLevel.INFO, result.message);
            return result;
        } finally {
            if (actionsTriggerSuppressionService != null) {
                try {
                    int cancelled;
                    try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(event.getTargetCredentialId())) {
                        cancelled = actionsTriggerSuppressionService.suppressAfterWrite(event.getTargetRepoUrl(), jobId);
                    }
                    if (cancelled > 0) {
                        logAudit(jobId, LogLevel.INFO, "Cancelled " + cancelled
                                + " mirror-triggered Actions run(s) on destination (final sweep).");
                    }
                } catch (Exception e) {
                    log.debug("Actions suppression final sweep: {}", e.getMessage());
                } finally {
                    actionsTriggerSuppressionService.endJob(jobId);
                }
            }
            activeStageProgress.remove();
            boolean keepRepoForLfs = syncSucceeded
                    && !result.fastPathShortCircuited
                    && pipeline != null
                    && !pipeline.isStageSettled(SyncPipelineState.LFS)
                    && SyncLaneRouter.includePairMetadata(event);
            if (syncSucceeded && storageTier == StorageTier.EPHEMERAL_STREAM && storageTieringService != null
                    && !keepRepoForLfs) {
                storageTieringService.cleanupEphemeralRepo(repoDir);
            }
        }
    }

    /**
     * Runs Git LFS discovery and blob transfer after PR/release metadata on full-mirror jobs.
     */
    public void executeLfsSync(SyncEventMessage event, Long jobId, SyncPipelineState pipeline,
                               SyncResult result, boolean resumingJob, String resumeStageId) {
        if (pipeline == null || result == null || !result.success || result.fastPathShortCircuited) {
            return;
        }
        if (!shouldRunGitStage(resumingJob, pipeline, resumeStageId, SyncPipelineState.LFS)) {
            logAudit(jobId, LogLevel.INFO, "Skipping Git LFS — already completed for this job.");
            return;
        }

        RepoMapping mapping = event.getMappingId() != null
                ? repoMappingRepository.findById(event.getMappingId()).orElse(null)
                : null;
        StorageTier storageTier = StorageTier.AUTO_LRU;
        if (mapping != null && mapping.getStorageTier() != null) {
            storageTier = mapping.getStorageTier();
        }
        File repoDir = null;
        if (storageTieringService != null) {
            repoDir = storageTieringService.resolveRepoDirectory(event.getMappingId(), storageTier);
        }
        try {
            if (repoDir == null) {
                repoDir = getOrCreateBareRepoDir(event.getMappingId());
            }
        } catch (IOException e) {
            logAudit(jobId, LogLevel.WARN, "Could not open mirror for LFS: " + e.getMessage());
            return;
        }

        SyncJob jobRecord = jobId != null ? syncJobRepository.findById(jobId).orElse(null) : null;
        JobStageProgress stageProgress = jobRecord != null
                ? jobExecutionStateService.loadStageProgress(jobRecord)
                : JobStageProgress.empty();
        activeStageProgress.set(stageProgress);
        SyncCheckpointStage checkpoint = mapping != null
                ? syncCheckpointService.getStage(mapping)
                : SyncCheckpointStage.NONE;

        pipeline.markCurrent(SyncPipelineState.LFS, "discovering pointers");
        broadcastPipeline(jobId, event.getMappingId(), pipeline);
        try (Git git = initOrOpenBareGit(repoDir, event.getSourceRepoUrl(), event.getTargetRepoUrl())) {
            if (gitLfsSyncService == null) {
                pipeline.markSkipped(SyncPipelineState.LFS, "LFS service unavailable");
            } else {
                GitLfsSyncService.LfsProgressListener lfsProgress = (phase, detail) -> {
                    throwIfStopRequested(jobId);
                    String label = "discover".equals(phase) ? "discovering pointers" : "transferring blobs";
                    pipeline.markCurrent(SyncPipelineState.LFS, label + " · " + detail);
                    broadcastPipeline(jobId, event.getMappingId(), pipeline);
                    if ("discover".equals(phase)
                            && (detail.startsWith("scanning ") || detail.startsWith("discovered "))) {
                        logAudit(jobId, LogLevel.INFO, "Git LFS " + detail + ".");
                    }
                };
                java.util.Set<String> completedLfsOids = new java.util.LinkedHashSet<>(stageProgress.getCompletedLfsOids());
                if (completedLfsOids.isEmpty() && mapping != null) {
                    completedLfsOids.addAll(syncCheckpointService.loadCompletedLfsOids(mapping));
                }
                List<GitLfsSyncService.LfsObject> lfsObjects;
                boolean hasJobDiscovery = stageProgress.getDiscoveredLfsBlob() != null
                        && !stageProgress.getDiscoveredLfsBlob().isBlank();
                if (hasJobDiscovery) {
                    lfsObjects = SyncCheckpointService.parseLfsObjects(stageProgress.getDiscoveredLfsBlob());
                    pipeline.markCurrent(SyncPipelineState.LFS,
                            "cached discovery · " + lfsObjects.size() + " pointer(s)");
                    broadcastPipeline(jobId, event.getMappingId(), pipeline);
                    logAudit(jobId, LogLevel.INFO, "Using job-cached LFS discovery: " + lfsObjects.size() + " pointer(s).");
                } else if (!resumingJob && checkpoint.ordinal() >= SyncCheckpointStage.LFS_DISCOVERY_DONE.ordinal()) {
                    lfsObjects = syncCheckpointService.loadDiscoveredLfs(mapping);
                    pipeline.markCurrent(SyncPipelineState.LFS,
                            "cached discovery · " + lfsObjects.size() + " pointer(s)");
                    broadcastPipeline(jobId, event.getMappingId(), pipeline);
                    logAudit(jobId, LogLevel.INFO, "Using cached LFS discovery: " + lfsObjects.size() + " pointer(s).");
                } else {
                    lfsObjects = gitLfsSyncService.discoverLfsPointers(
                            git.getRepository(), lfsProgress, () -> isStopRequested(jobId), jobId);
                    if (lfsObjects != null && !lfsObjects.isEmpty()) {
                        stageProgress.setDiscoveredLfsBlob(SyncCheckpointService.serializeLfsObjects(lfsObjects));
                        syncCheckpointService.persistDiscoveredLfs(event.getMappingId(), lfsObjects);
                    }
                }
                if (lfsObjects != null && !lfsObjects.isEmpty()) {
                    List<GitLfsSyncService.LfsObject> pending = lfsObjects.stream()
                            .filter(o -> !completedLfsOids.contains(o.oid()))
                            .toList();
                    logAudit(jobId, LogLevel.INFO, "Discovered " + lfsObjects.size() + " Git LFS pointer(s). "
                            + pending.size() + " remaining to transfer.");
                    var lfsStats = gitLfsSyncService.syncLfsObjects(
                            event.getSourceRepoUrl(), event.getTargetRepoUrl(), pending,
                            lfsProgress, () -> isStopRequested(jobId), jobId,
                            resolveSideToken(event.getTokenA(), event.getSourceCredentialId()),
                            resolveSideToken(event.getTokenB(), event.getTargetCredentialId()),
                            oid -> {
                                completedLfsOids.add(oid);
                                stageProgress.getCompletedLfsOids().add(oid);
                                syncCheckpointService.appendCompletedLfsOid(event.getMappingId(), oid);
                                persistPipeline(jobId, pipeline, null);
                            });
                    result.lfsObjectsCount = lfsObjects.size();
                    result.lfsSyncedCount = (int) lfsObjects.stream()
                            .filter(o -> completedLfsOids.contains(o.oid()))
                            .count();
                    if (result.lfsSyncedCount == 0 && lfsStats.count() > 0) {
                        result.lfsSyncedCount = lfsStats.count();
                    }
                    result.lfsBytes = lfsStats.bytes();
                    if (lfsStats.failed() > 0) {
                        logAudit(jobId, LogLevel.WARN, "Git LFS transfer completed with "
                                + lfsStats.failed() + " failure(s) out of " + lfsObjects.size() + " object(s).");
                    }
                    pipeline.markDone(SyncPipelineState.LFS, result.lfsSyncedCount + "/" + lfsObjects.size()
                            + " object(s) on destination"
                            + (lfsStats.failed() > 0 ? " · " + lfsStats.failed() + " failed" : ""));
                    logAudit(jobId, LogLevel.INFO, "Git LFS synchronization completed: "
                            + result.lfsSyncedCount + "/" + lfsObjects.size() + " object(s) on destination, "
                            + formatBytes(lfsStats.bytes()) + " replicated.");
                } else {
                    pipeline.markSkipped(SyncPipelineState.LFS, "No LFS pointers");
                    logAudit(jobId, LogLevel.INFO, "No Git LFS pointers found; skipping binary blob sync.");
                }
            }
            persistPipeline(jobId, pipeline, result.rejectedPushRefs);
            result.bytesTransferred += result.lfsBytes;
            result.pipeline = pipeline;
            result.pipelineJson = pipeline.toJson();
        } catch (JobCancelledException e) {
            throw e;
        } catch (Exception lfsEx) {
            pipeline.markDone(SyncPipelineState.LFS, "Completed with notice");
            logAudit(jobId, LogLevel.WARN, "Git LFS sync completed with notice: " + lfsEx.getMessage());
            persistPipeline(jobId, pipeline, result.rejectedPushRefs);
            result.pipeline = pipeline;
            result.pipelineJson = pipeline.toJson();
        } finally {
            activeStageProgress.remove();
            if (storageTier == StorageTier.EPHEMERAL_STREAM && storageTieringService != null) {
                storageTieringService.cleanupEphemeralRepo(repoDir);
            }
        }
    }

    private boolean shouldRunGitStage(boolean resumingJob, SyncPipelineState pipeline,
                                      String resumeStageId, String stageId) {
        if (!resumingJob) {
            return true;
        }
        return jobExecutionStateService.shouldExecuteStage(pipeline, resumeStageId, stageId);
    }

    public static class IsolatedRef {
        public ConflictKind kind;
        public String originalRef;
        public String isolatedBranch;
        public String sourceSha;
        public String destSha;
        public TrunkConflictPolicy policy;
        public TrunkPushAction action;
    }

    public enum TrunkPushAction {
        PUSH,
        FORCE,
        ISOLATE,
        SKIP
    }

    /**
     * Non-fast-forward trunk updates isolate to {@code sync-conflict/*} unless the operator
     * explicitly requested overwriting destination from source.
     */
    static boolean shouldIsolateDivergedTrunk(boolean isFastForward, boolean overwriteFromSource) {
        return decideTrunkPush(isFastForward, overwriteFromSource, TrunkConflictPolicy.ISOLATE) == TrunkPushAction.ISOLATE;
    }

    static TrunkPushAction decideTrunkPush(boolean isFastForward, boolean overwriteFromSource,
                                           TrunkConflictPolicy policy) {
        if (isFastForward) {
            return TrunkPushAction.PUSH;
        }
        if (overwriteFromSource) {
            return TrunkPushAction.FORCE;
        }
        TrunkConflictPolicy p = policy != null ? policy : TrunkConflictPolicy.ISOLATE;
        return switch (p) {
            case ORIGIN_WINS -> TrunkPushAction.FORCE;
            case FAIL_JOB -> TrunkPushAction.SKIP;
            case ISOLATE -> TrunkPushAction.ISOLATE;
        };
    }

    static TrunkConflictPolicy policyOf(RepoMapping mapping) {
        if (mapping == null || mapping.getTrunkConflictPolicy() == null) {
            return TrunkConflictPolicy.ISOLATE;
        }
        return mapping.getTrunkConflictPolicy();
    }

    public static boolean isTrunkBranch(String branch) {
        if (branch == null) return false;
        String b = branch.trim().toLowerCase();
        return b.equals("main") || b.equals("master") || b.equals("trunk") || b.startsWith("release") || b.startsWith("prod");
    }

    static String isolatedConflictBranch(String branchName, Date at) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss");
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String timestamp = fmt.format(at != null ? at : new Date());
        return "sync-conflict/" + branchName + "-" + timestamp;
    }

    public static boolean isSyncConflictBranch(String branchOrRef) {
        return RefOriginService.isSyncConflictBranch(branchOrRef);
    }

    private File getOrCreateBareRepoDir(Long mappingId) throws IOException {
        Path base = Paths.get(workspaceDir);
        if (!Files.exists(base)) {
            Files.createDirectories(base);
        }
        File repoDir = new File(base.toFile(), "pair-" + mappingId + ".git");
        return repoDir;
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
        config.setString("remote", "source", "fetch", "+refs/heads/*:refs/heads/*");
        config.setString("remote", "target", "url", targetUrl);
        config.setInt("http", null, "postBuffer", Math.max(1024 * 1024, httpPostBufferBytes));
        config.save();

        return git;
    }

    private CredentialsProvider createCredentialsProvider(SyncEventMessage event, String repoUrl, String explicitToken) {
        Long credId = credentialIdFor(event, repoUrl);
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credId)) {
            if (scmProviderFacade != null) {
                return scmProviderFacade.getGitCredentials(repoUrl, explicitToken);
            }
            return null;
        }
    }

    private static Long credentialIdFor(SyncEventMessage event, String repoUrl) {
        if (event == null || repoUrl == null) {
            return null;
        }
        if (RepoMappingService.sameRepo(repoUrl, event.getSourceRepoUrl())) {
            return event.getSourceCredentialId();
        }
        if (RepoMappingService.sameRepo(repoUrl, event.getTargetRepoUrl())) {
            return event.getTargetCredentialId();
        }
        return event.getSourceCredentialId();
    }

    private String resolveSideToken(String explicit, Long credentialId) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        if (credentialId == null || scmCredentialService == null) {
            return null;
        }
        return scmCredentialService.resolveAccessToken(credentialId);
    }

    private void assertCredentialCanAccess(Long credentialId, String repoUrl) {
        if (credentialId == null || repoUrl == null || scmCredentialService == null) {
            return;
        }
        if (!ScmCredentialService.isGithubOrGhesUrl(repoUrl)) {
            return;
        }
        scmCredentialService.assertRepoAccessible(scmCredentialService.requireEnabled(credentialId), repoUrl);
    }

    /**
     * @return true if the source was fetched anonymously (public HTTPS)
     */
    private boolean fetchSourcePublicFirst(Git git, CredentialsProvider sourceCreds, Boolean cachedPublicRead,
                                          LiveGitProgressMonitor monitor, SyncEventMessage event,
                                          boolean includePullHeads) throws Exception {
        boolean knownPrivate = Boolean.FALSE.equals(cachedPublicRead);
        if (!knownPrivate) {
            try {
                doSourceFetch(git, null, monitor, event, includePullHeads);
                return true;
            } catch (Exception e) {
                if (sourceCreds == null || !isAuthFailure(e)) {
                    throw e;
                }
                log.debug("Anonymous source fetch failed ({}), retrying with credentials", e.getMessage());
            }
        }
        if (sourceCreds == null) {
            throw new IllegalStateException("Source repository is not publicly readable and no credentials are configured.");
        }
        doSourceFetch(git, sourceCreds, monitor, event, includePullHeads);
        return false;
    }

    private void doSourceFetch(Git git, CredentialsProvider creds, LiveGitProgressMonitor monitor,
                               SyncEventMessage event, boolean includePullHeads) throws Exception {
        RefSpec[] refSpecs = sourceFetchRefSpecs(event, includePullHeads);
        git.fetch()
                .setRemote("source")
                .setRefSpecs(refSpecs)
                .setCredentialsProvider(creds)
                .setProgressMonitor(monitor)
                .setTimeout(httpTimeoutSeconds)
                .setRemoveDeletedRefs(true)
                .call();
    }

    static RefSpec[] sourceFetchRefSpecs(SyncEventMessage event) {
        return sourceFetchRefSpecs(event, true);
    }

    static RefSpec[] sourceFetchRefSpecs(SyncEventMessage event, boolean includePullHeads) {
        if (event != null && !SyncLaneRouter.isFullMirror(event)) {
            String ref = event.getRef();
            if (ref != null && !ref.isBlank()) {
                return new RefSpec[]{new RefSpec("+" + ref + ":" + ref)};
            }
            String branch = event.getBranch();
            if (branch != null && !branch.isBlank() && !"*".equals(branch)) {
                String headRef = branch.startsWith("refs/") ? branch : "refs/heads/" + branch;
                return new RefSpec[]{new RefSpec("+" + headRef + ":" + headRef)};
            }
        }
        if (includePullHeads) {
            return new RefSpec[]{
                    new RefSpec("+refs/heads/*:refs/heads/*"),
                    new RefSpec("+refs/tags/*:refs/tags/*"),
                    new RefSpec("+refs/notes/*:refs/notes/*"),
                    new RefSpec("+refs/pull/*/head:refs/pull/*/head")
            };
        }
        return new RefSpec[]{
                new RefSpec("+refs/heads/*:refs/heads/*"),
                new RefSpec("+refs/tags/*:refs/tags/*"),
                new RefSpec("+refs/notes/*:refs/notes/*")
        };
    }

    private boolean runSourceFetchWithHeartbeat(Long jobId, LiveGitProgressMonitor monitor,
                                                Callable<Boolean> fetchTask) throws Exception {
        return runGitOpWithHeartbeat(jobId,
                () -> {
                    String live = monitor != null ? monitor.lastProgressMessage() : null;
                    if (live != null && !live.isBlank()) {
                        return live + " · still in progress...";
                    }
                    return "Source fetch · still negotiating / finalizing pack files on disk...";
                },
                fetchTask);
    }

    private <T> T runGitOpWithHeartbeat(Long jobId, java.util.function.Supplier<String> heartbeatMessage,
                                        Callable<T> task) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-heartbeat-" + jobId);
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> {
                    String msg = heartbeatMessage != null ? heartbeatMessage.get() : null;
                    if (msg == null || msg.isBlank()) {
                        msg = "Git operation · still in progress...";
                    }
                    logAudit(jobId, LogLevel.INFO, msg);
                },
                30, 30, TimeUnit.SECONDS);
        try {
            return task.call();
        } finally {
            heartbeat.cancel(false);
            scheduler.shutdownNow();
        }
    }

    private List<RefSpec> expandFullMirrorRefSpecs(Git git, Map<String, String> alreadyPushed, boolean targetReachable,
                                                  RepoMapping mapping, SyncEventMessage event, SyncResult result,
                                                  ThrottledAuditProgress progress) throws Exception {
        Repository repo = git.getRepository();
        List<String> headNames = new ArrayList<>(BareRepoHousekeeping.listHeadBranchNames(repo));
        headNames.sort(Comparator.comparingInt(name -> defaultBranchSortKey("refs/heads/" + name)));

        List<RefSpec> specs = new ArrayList<>();
        int totalBranches = headNames.size();
        int skipped = 0;
        for (int i = 0; i < headNames.size(); i++) {
            String branch = headNames.get(i);
            if (progress != null) {
                progress.maybeEmit("Conflict check · comparing branches " + (i + 1) + "/" + totalBranches
                        + " · " + specs.size() + " need push · " + skipped + " already matched");
            }
            String refName = "refs/heads/" + branch;
            if (shouldOmitHead(mapping, event, refName)) {
                skipped++;
                continue;
            }
            Ref ref = repo.exactRef(refName);
            if (ref == null || ref.getObjectId() == null) {
                skipped++;
                continue;
            }
            int before = specs.size();
            maybeAddRefSpec(git, specs, ref, alreadyPushed, targetReachable, mapping, event, result);
            if (specs.size() == before) {
                skipped++;
            }
        }
        List<Ref> tagRefs = new ArrayList<>(repo.getRefDatabase().getRefsByPrefix("refs/tags/"));
        int totalTags = tagRefs.size();
        for (int i = 0; i < tagRefs.size(); i++) {
            if (progress != null) {
                progress.maybeEmit("Conflict check · comparing tags " + (i + 1) + "/" + totalTags
                        + " · " + specs.size() + " need push");
            }
            maybeAddRefSpec(git, specs, tagRefs.get(i), alreadyPushed, targetReachable, mapping, event, result);
        }
        List<Ref> noteRefs = new ArrayList<>(repo.getRefDatabase().getRefsByPrefix("refs/notes/"));
        for (int i = 0; i < noteRefs.size(); i++) {
            if (progress != null) {
                progress.maybeEmit("Conflict check · comparing notes " + (i + 1) + "/" + noteRefs.size()
                        + " · " + specs.size() + " need push");
            }
            maybeAddRefSpec(git, specs, noteRefs.get(i), alreadyPushed, targetReachable, mapping, event, result);
        }
        if (progress != null) {
            progress.forceEmit("Conflict check · " + specs.size() + " ref(s) need push or update"
                    + " (" + skipped + " branch tip(s) already matched or omitted).");
        }
        return specs;
    }

    private List<RefSpec> pruneSpecsForOriginDeletes(Git git, RepoMapping mapping, SyncEventMessage event,
                                                     boolean targetReachable) {
        List<RefSpec> specs = new ArrayList<>();
        if (!targetReachable || mapping == null || refOriginService == null) {
            return specs;
        }
        PairSide sourceSide = sourceSideOf(mapping, event);
        Set<String> local = localHeadBranchNames(git);
        for (String destBranch : destHeadBranchNames(git)) {
            if (local.contains(destBranch)) {
                continue;
            }
            String ref = "refs/heads/" + destBranch;
            if (refOriginService.shouldPropagateDelete(mapping, sourceSide, ref)) {
                specs.add(new RefSpec(":" + ref));
            }
        }
        return specs;
    }

    private boolean shouldOmitHead(RepoMapping mapping, SyncEventMessage event, String branchOrRef) {
        if (mapping == null || refOriginService == null || branchOrRef == null) {
            return false;
        }
        return refOriginService.shouldOmitHeadPush(
                mapping, sourceSideOf(mapping, event), destSideOf(mapping, event), branchOrRef);
    }

    private static PairSide sourceSideOf(RepoMapping mapping, SyncEventMessage event) {
        String source = event != null ? event.getSourceRepoUrl() : null;
        return RefOriginService.sourceSide(mapping, source);
    }

    private static PairSide destSideOf(RepoMapping mapping, SyncEventMessage event) {
        PairSide source = sourceSideOf(mapping, event);
        return source == PairSide.B ? PairSide.A : PairSide.B;
    }

    private Set<String> localHeadBranchNames(Git git) {
        Set<String> names = new HashSet<>();
        try {
            names.addAll(BareRepoHousekeeping.listHeadBranchNames(git.getRepository()));
        } catch (Exception e) {
            log.debug("Could not list local heads: {}", e.getMessage());
        }
        return names;
    }

    private Set<String> destHeadBranchNames(Git git) {
        Set<String> names = new HashSet<>();
        try {
            names.addAll(BareRepoHousekeeping.listRefSuffixesWithPrefix(
                    git.getRepository(), "refs/remotes/target/"));
        } catch (Exception e) {
            log.debug("Could not list dest heads: {}", e.getMessage());
        }
        return names;
    }

    private void fillPairRefCounts(Git git, SyncResult result) {
        if (git == null || git.getRepository() == null || result == null) {
            return;
        }
        try {
            Repository repo = git.getRepository();
            BareRepoHousekeeping.PairRefNames names = BareRepoHousekeeping.listPairRefNames(repo);
            int inSync = 0;
            int pending = 0;
            int destOnly = 0;
            Set<String> union = new HashSet<>();
            union.addAll(names.sourceBranches());
            union.addAll(names.destBranches());
            for (String branch : union) {
                ObjectId sourceId = repo.resolve("refs/heads/" + branch);
                if (sourceId == null) {
                    sourceId = repo.resolve("refs/remotes/source/" + branch);
                }
                ObjectId destId = repo.resolve("refs/remotes/target/" + branch);
                if (sourceId != null && destId != null) {
                    if (sourceId.equals(destId)) {
                        inSync++;
                    } else {
                        pending++;
                    }
                } else if (sourceId != null) {
                    pending++;
                } else {
                    destOnly++;
                }
            }
            // Dest tracking refs are updated as each push batch succeeds (applyPushedRefToDestTracking).
            result.sourceBranchesCount = names.sourceBranchCount();
            result.destBranchesCount = names.destBranchCount();
            result.inSyncBranchesCount = inSync;
            result.pendingBranchesCount = pending;
            result.destOnlyBranchesCount = destOnly;
            result.sourceTagsCount = names.sourceTags().size();
            result.destTagsCount = names.destTags().size();
        } catch (Exception e) {
            log.debug("Could not fill pair ref counts: {}", e.getMessage());
        }
    }

    private void maybeAddRefSpec(Git git, List<RefSpec> specs, Ref ref, Map<String, String> alreadyPushed,
                                 boolean targetReachable, RepoMapping mapping, SyncEventMessage event, SyncResult result) {
        if (ref == null || ref.getName() == null || ref.getObjectId() == null) {
            return;
        }
        String name = ref.getName();
        String sha = ObjectId.toString(ref.getObjectId());
        if (name.startsWith("refs/heads/")) {
            String branch = name.substring("refs/heads/".length());
            String destSha = null;
            ObjectId destId = null;
            if (targetReachable) {
                try {
                    Ref dest = git.getRepository().exactRef("refs/remotes/target/" + branch);
                    if (dest != null && dest.getObjectId() != null) {
                        destId = dest.getObjectId();
                        destSha = ObjectId.toString(destId);
                    }
                } catch (Exception e) {
                    log.debug("Could not inspect dest ref {}: {}", name, e.getMessage());
                }
            }
            if (shouldSkipRef(name, sha, destSha, alreadyPushed, targetReachable)) {
                return;
            }
            if (isTrunkBranch(branch) && destId != null && event != null) {
                RefSpec trunkSpec = resolveTrunkPushSpec(git, name, branch, ref.getObjectId(), destId, event, mapping, result,
                        event.getJobId());
                if (trunkSpec != null) {
                    specs.add(trunkSpec);
                }
                return;
            }
            specs.add(new RefSpec("+" + name + ":" + name));
            return;
        }
        if (name.startsWith("refs/tags/") && targetReachable) {
            String tagName = name.substring("refs/tags/".length());
            try {
                Ref destTag = git.getRepository().exactRef("refs/remotes/target-tags/" + tagName);
                if (destTag != null && destTag.getObjectId() != null) {
                    if (!destTag.getObjectId().equals(ref.getObjectId())) {
                        IsolatedRef iso = new IsolatedRef();
                        iso.kind = ConflictKind.TAG;
                        iso.originalRef = name;
                        iso.sourceSha = sha;
                        iso.destSha = ObjectId.toString(destTag.getObjectId());
                        iso.policy = TrunkConflictPolicy.FAIL_JOB;
                        iso.action = TrunkPushAction.SKIP;
                        result.conflictIsolated = true;
                        result.isolatedRefs.add(iso);
                        logAudit(event != null ? event.getJobId() : null, LogLevel.WARN,
                                "Tag '" + tagName + "' differs on destination (" + iso.destSha.substring(0, 7)
                                        + ") vs source (" + sha.substring(0, 7) + "); skipping force-move of published tag.");
                        return;
                    }
                    // Same object on dest — do not re-push (full sync used to queue every matching tag).
                    return;
                }
            } catch (Exception e) {
                log.debug("Could not inspect dest tag {}: {}", name, e.getMessage());
            }
        }
        if (name.startsWith("refs/notes/") && targetReachable) {
            String noteSuffix = name.substring("refs/notes/".length());
            try {
                Ref destNote = git.getRepository().exactRef("refs/remotes/target-notes/" + noteSuffix);
                if (destNote != null && destNote.getObjectId() != null) {
                    if (destNote.getObjectId().equals(ref.getObjectId())) {
                        return;
                    }
                    maybeAddMatchingSpec(git, specs, ref, alreadyPushed, targetReachable,
                            ObjectId.toString(destNote.getObjectId()));
                    return;
                }
            } catch (Exception e) {
                log.debug("Could not inspect dest note {}: {}", name, e.getMessage());
            }
        }
        String destSha = null;
        maybeAddMatchingSpec(git, specs, ref, alreadyPushed, targetReachable, destSha);
    }

    private void maybeAddMatchingSpec(Git git, List<RefSpec> specs, Ref ref, Map<String, String> alreadyPushed,
                                      boolean targetReachable, String destShaOrNull) {
        String name = ref.getName();
        String sha = ObjectId.toString(ref.getObjectId());
        if (shouldSkipRef(name, sha, destShaOrNull, alreadyPushed, targetReachable)) {
            return;
        }
        specs.add(new RefSpec("+" + name + ":" + name));
    }

    private RefSpec resolveTrunkPushSpec(Git git, String localRefName, String branchName,
                                         ObjectId localId, ObjectId destId, SyncEventMessage event,
                                         RepoMapping mapping, SyncResult result, Long jobId) {
        if (localId.equals(destId)) {
            logAudit(jobId, LogLevel.INFO, "Branch '" + branchName + "' is already up to date on target.");
            return null;
        }
        boolean fastForward = false;
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            RevCommit localCommit = revWalk.parseCommit(localId);
            RevCommit remoteCommit = revWalk.parseCommit(destId);
            fastForward = revWalk.isMergedInto(remoteCommit, localCommit);
        } catch (Exception ex) {
            logAudit(jobId, LogLevel.WARN, "Could not compute RevWalk fast-forward ancestor: " + ex.getMessage());
            return new RefSpec("+" + localRefName + ":" + localRefName);
        }
        TrunkConflictPolicy policy = policyOf(mapping);
        TrunkPushAction action = decideTrunkPush(fastForward, event != null && event.isOverwriteFromSource(), policy);
        String sourceSha = ObjectId.toString(localId);
        String destSha = ObjectId.toString(destId);
        if (action == TrunkPushAction.PUSH) {
            return new RefSpec("+" + localRefName + ":" + localRefName);
        }
        if (action == TrunkPushAction.FORCE) {
            logAudit(jobId, LogLevel.WARN, String.format(
                    "Force-pushing source '%s' (%s) onto destination (was %s) policy=%s overwrite=%s.",
                    branchName, sourceSha.substring(0, 7), destSha.substring(0, 7), policy,
                    event != null && event.isOverwriteFromSource()
            ));
            IsolatedRef iso = new IsolatedRef();
            iso.kind = ConflictKind.GIT_REF;
            iso.originalRef = localRefName;
            iso.sourceSha = sourceSha;
            iso.destSha = destSha;
            iso.policy = policy;
            iso.action = TrunkPushAction.FORCE;
            result.isolatedRefs.add(iso);
            return new RefSpec("+" + localRefName + ":" + localRefName);
        }
        if (action == TrunkPushAction.SKIP) {
            IsolatedRef iso = new IsolatedRef();
            iso.kind = ConflictKind.GIT_REF;
            iso.originalRef = localRefName;
            iso.sourceSha = sourceSha;
            iso.destSha = destSha;
            iso.policy = policy;
            iso.action = TrunkPushAction.SKIP;
            result.conflictIsolated = true;
            result.isolatedRefs.add(iso);
            logAudit(jobId, LogLevel.WARN, String.format(
                    "DIVERGENCE DETECTED on trunk '%s' (%s vs dest %s). Policy FAIL_JOB: not pushing this ref.",
                    branchName, sourceSha.substring(0, 7), destSha.substring(0, 7)
            ));
            return null;
        }
        String conflictBranch = isolatedConflictBranch(branchName, new Date());
        IsolatedRef iso = new IsolatedRef();
        iso.kind = ConflictKind.GIT_REF;
        iso.originalRef = localRefName;
        iso.isolatedBranch = conflictBranch;
        iso.sourceSha = sourceSha;
        iso.destSha = destSha;
        iso.policy = policy;
        iso.action = TrunkPushAction.ISOLATE;
        result.conflictIsolated = true;
        result.isolatedBranch = conflictBranch;
        result.isolatedRefs.add(iso);
        logAudit(jobId, LogLevel.WARN, String.format(
                "DIVERGENCE DETECTED on trunk branch '%s'! Remote commit (%s) is not ancestor of local commit (%s). "
                        + "Pushing non-destructively to isolated branch '%s' to prevent data loss.",
                branchName, destSha.substring(0, 7), sourceSha.substring(0, 7), conflictBranch
        ));
        return new RefSpec("+" + localRefName + ":refs/heads/" + conflictBranch);
    }

    private void persistAndOpenConflicts(SyncResult result, RepoMapping mapping, SyncEventMessage event) {
        if (result == null || result.isolatedRefs == null || result.isolatedRefs.isEmpty() || syncConflictService == null) {
            return;
        }
        Long mappingId = mapping != null ? mapping.getId() : (event != null ? event.getMappingId() : null);
        Long jobId = event != null ? event.getJobId() : null;
        String destUrl = event != null ? event.getTargetRepoUrl() : null;
        for (IsolatedRef iso : result.isolatedRefs) {
            if (iso.action == TrunkPushAction.FORCE) {
                continue;
            }
            String message = iso.kind == ConflictKind.TAG
                    ? "Published tag differs; force-move skipped"
                    : (iso.action == TrunkPushAction.SKIP
                    ? "Diverged trunk not pushed (FAIL_JOB policy)"
                    : "Incoming commits isolated to " + iso.isolatedBranch);
            var row = syncConflictService.recordGitRefConflict(
                    mappingId, jobId, iso.kind, iso.policy, iso.originalRef,
                    iso.sourceSha, iso.destSha, iso.isolatedBranch, destUrl, message);
            if (row != null && iso.action == TrunkPushAction.ISOLATE && mapping != null && destUrl != null
                    && !isSimulationOrTestUrl(destUrl)) {
                try {
                    syncConflictService.openConflictPr(row, mapping, destUrl);
                } catch (Exception e) {
                    log.warn("Could not open conflict PR for {}: {}", iso.isolatedBranch, e.getMessage());
                }
            }
        }
    }

    static String conflictSummaryMessage(SyncResult result) {
        if (result == null) {
            return "Split-brain divergence isolated.";
        }
        if (result.isolatedRefs == null || result.isolatedRefs.isEmpty()) {
            return "Split-brain divergence detected. Isolated branch: " + result.isolatedBranch;
        }
        List<String> parts = new ArrayList<>();
        for (IsolatedRef iso : result.isolatedRefs) {
            if (iso.action == TrunkPushAction.FORCE) {
                continue;
            }
            String name = iso.isolatedBranch != null ? iso.isolatedBranch
                    : (iso.originalRef != null ? iso.originalRef : "ref");
            parts.add(name);
        }
        if (parts.isEmpty()) {
            return "Split-brain divergence isolated.";
        }
        return "Split-brain divergence isolated on: " + String.join(", ", parts);
    }

    /**
     * Skip when destination tip already matches. When dest is reachable but tip is missing,
     * never trust the resume ledger alone (poisoned ledger must not hide a create/update).
     * When dest is not reachable, fall back to the resume ledger only.
     */
    static boolean shouldSkipRef(String refName, String localSha, String destShaOrNull,
                                 Map<String, String> ledger, boolean targetReachable) {
        if (localSha == null) {
            return false;
        }
        if (targetReachable && destShaOrNull != null && localSha.equals(destShaOrNull)) {
            return true;
        }
        if (targetReachable) {
            // Missing dest tip → must push (create). Ledger match is not enough.
            return false;
        }
        return ledger != null && localSha.equals(ledger.get(refName));
    }

    /**
     * Skip source fetch when the local bare mirror already has packs and heads (warm cache),
     * or when a mid-push resume ledger is present. Callers must still probe source tips
     * ({@link #sourceRemoteTipsDifferFromLocal}) before skipping — warm packs alone miss
     * new commits. Use forceSourceFetch / Start fresh for an unconditional re-fetch.
     */
    static boolean shouldSkipSourceFetch(boolean isFullMirror, boolean localPacksExist, boolean hasLocalHeads,
                                         Map<String, String> pushLedger) {
        if (!isFullMirror || !localPacksExist) {
            return false;
        }
        return hasLocalHeads || (pushLedger != null && !pushLedger.isEmpty());
    }

    /**
     * Cheap advertisement-only check: ls-remote heads/tags/notes and compare to local tips.
     */
    private TipProbeResult probeSourceTips(Git git, CredentialsProvider sourceCreds,
                                           Boolean cachedPublicRead, Long jobId) throws Exception {
        Map<String, String> remoteTips = lsRemoteSourceTipsPublicFirst(git, sourceCreds, cachedPublicRead);
        Map<String, String> localTips = collectLocalProbeTips(git);
        TipProbeResult result = TipProbeResult.compare(localTips, remoteTips);
        if (result.differ()) {
            log.debug("[job-{}] Tip probe: {}", jobId, result.summary());
        }
        return result;
    }

    private Map<String, String> lsRemoteSourceTipsPublicFirst(Git git, CredentialsProvider sourceCreds,
                                                              Boolean cachedPublicRead) throws Exception {
        boolean knownPrivate = Boolean.FALSE.equals(cachedPublicRead);
        if (!knownPrivate) {
            try {
                return lsRemoteSourceTips(git, null);
            } catch (Exception e) {
                if (sourceCreds == null || !isAuthFailure(e)) {
                    throw e;
                }
                log.debug("Anonymous ls-remote failed ({}), retrying with credentials", e.getMessage());
            }
        }
        if (sourceCreds == null) {
            throw new IllegalStateException("Source tip probe needs credentials; none configured.");
        }
        return lsRemoteSourceTips(git, sourceCreds);
    }

    private Map<String, String> lsRemoteSourceTips(Git git, CredentialsProvider creds) throws Exception {
        // One advertisement round-trip (no packs). Filter to heads/tags/notes.
        Collection<Ref> refs = git.lsRemote()
                .setRemote("source")
                .setCredentialsProvider(creds)
                .setTimeout(httpTimeoutSeconds)
                .call();
        Map<String, String> tips = new LinkedHashMap<>();
        if (refs == null) {
            return tips;
        }
        for (Ref ref : refs) {
            if (ref == null || ref.getName() == null || ref.getObjectId() == null) {
                continue;
            }
            String name = ref.getName();
            if (!isProbeTipRef(name)) {
                continue;
            }
            tips.put(name, ObjectId.toString(ref.getObjectId()));
        }
        return tips;
    }

    static Map<String, String> collectLocalProbeTips(Git git) throws IOException {
        Map<String, String> tips = new LinkedHashMap<>();
        Repository repo = git.getRepository();
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/heads/")) {
            putProbeTip(tips, ref);
        }
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/tags/")) {
            putProbeTip(tips, ref);
        }
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/notes/")) {
            putProbeTip(tips, ref);
        }
        return tips;
    }

    private static void putProbeTip(Map<String, String> tips, Ref ref) {
        if (ref == null || ref.getName() == null || ref.getObjectId() == null) {
            return;
        }
        if (!isProbeTipRef(ref.getName())) {
            return;
        }
        tips.put(ref.getName(), ObjectId.toString(ref.getObjectId()));
    }

    static boolean isProbeTipRef(String name) {
        if (name == null) {
            return false;
        }
        // Skip symbolic/peeled advertisement noise
        if (name.endsWith("^{}")) {
            return false;
        }
        return name.startsWith("refs/heads/")
                || name.startsWith("refs/tags/")
                || name.startsWith("refs/notes/");
    }

    /**
     * True when any probed tip differs, appears only on remote, or exists only locally
     * (source deleted the ref). Empty remote with non-empty local also differs.
     */
    static boolean remoteTipsDifferFromLocal(Map<String, String> localTips, Map<String, String> remoteTips) {
        return TipProbeResult.compare(localTips, remoteTips).differ();
    }

    record TipProbeResult(int moved, int added, int removed, List<String> samples) {
        boolean differ() {
            return moved > 0 || added > 0 || removed > 0;
        }

        String summary() {
            String base = moved + " moved, " + added + " added, " + removed + " removed";
            if (samples == null || samples.isEmpty()) {
                return base;
            }
            return base + "; e.g. " + String.join(", ", samples);
        }

        static TipProbeResult compare(Map<String, String> localTips, Map<String, String> remoteTips) {
            Map<String, String> local = localTips != null ? localTips : Map.of();
            Map<String, String> remote = remoteTips != null ? remoteTips : Map.of();
            int moved = 0;
            int added = 0;
            int removed = 0;
            List<String> samples = new ArrayList<>();
            Set<String> names = new HashSet<>();
            names.addAll(local.keySet());
            names.addAll(remote.keySet());
            for (String name : names) {
                String l = local.get(name);
                String r = remote.get(name);
                if (l == null && r != null) {
                    added++;
                    if (samples.size() < 3) {
                        samples.add(shortRef(name) + " +");
                    }
                } else if (l != null && r == null) {
                    removed++;
                    if (samples.size() < 3) {
                        samples.add(shortRef(name) + " -");
                    }
                } else if (l != null && !l.equalsIgnoreCase(r)) {
                    moved++;
                    if (samples.size() < 3) {
                        samples.add(shortRef(name) + " → " + r.substring(0, Math.min(7, r.length())));
                    }
                }
            }
            return new TipProbeResult(moved, added, removed, samples);
        }

        private static String shortRef(String name) {
            if (name == null) {
                return "?";
            }
            if (name.startsWith("refs/heads/")) {
                return name.substring("refs/heads/".length());
            }
            if (name.startsWith("refs/tags/")) {
                return "tag:" + name.substring("refs/tags/".length());
            }
            return name;
        }
    }

    /**
     * Copies legacy diff-inspection layout ({@code refs/remotes/source/*}) into {@code refs/heads/*}
     * when the sync engine's head namespace is empty so full-mirror push can see branch tips.
     */
    static void normalizeLegacySourceBranchRefs(Git git) throws IOException {
        Repository repo = git.getRepository();
        if (BareRepoHousekeeping.hasAnyHeadRefs(repo)) {
            return;
        }
        List<Ref> sourceRemotes = repo.getRefDatabase().getRefsByPrefix("refs/remotes/source/");
        if (sourceRemotes.isEmpty()) {
            return;
        }
        for (Ref ref : sourceRemotes) {
            if (ref.getObjectId() == null || ref.getName() == null) {
                continue;
            }
            String branch = ref.getName().substring("refs/remotes/source/".length());
            org.eclipse.jgit.lib.RefUpdate update = repo.updateRef("refs/heads/" + branch);
            update.setNewObjectId(ref.getObjectId());
            org.eclipse.jgit.lib.RefUpdate.Result updateResult = update.update();
            if (updateResult != org.eclipse.jgit.lib.RefUpdate.Result.NEW
                    && updateResult != org.eclipse.jgit.lib.RefUpdate.Result.FORCED
                    && updateResult != org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE) {
                log.debug("Could not normalize legacy source ref {}: {}", ref.getName(), updateResult);
            }
        }
    }

    static boolean hasLocalHeadRefs(Git git) {
        try {
            return BareRepoHousekeeping.hasAnyHeadRefs(git.getRepository());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Default branch as its own first batch, then remaining heads/tags/notes in groups of {@code batchSize}.
     */
    static List<List<RefSpec>> partitionPushBatches(List<RefSpec> specs, int batchSize) {
        List<List<RefSpec>> batches = new ArrayList<>();
        if (specs == null || specs.isEmpty()) {
            return batches;
        }
        int size = Math.max(1, batchSize);
        int start = 0;
        if (isDefaultBranchSpec(specs.get(0))) {
            batches.add(List.of(specs.get(0)));
            start = 1;
        }
        for (int i = start; i < specs.size(); i += size) {
            batches.add(new ArrayList<>(specs.subList(i, Math.min(i + size, specs.size()))));
        }
        return batches;
    }

    private static boolean isDefaultBranchSpec(RefSpec spec) {
        if (spec == null || spec.getSource() == null) return false;
        int key = defaultBranchSortKey(spec.getSource());
        return key <= 2;
    }

    static int defaultBranchSortKey(String refName) {
        if (refName == null) return 99;
        String n = refName.toLowerCase();
        if (n.equals("refs/heads/main")) return 0;
        if (n.equals("refs/heads/master")) return 1;
        if (n.equals("refs/heads/trunk")) return 2;
        if (n.startsWith("refs/heads/release")) return 3;
        if (n.startsWith("refs/heads/")) return 4;
        if (n.startsWith("refs/tags/")) return 5;
        return 6;
    }

    private PushBatchResult pushBatchWithRetries(Git git, SyncEventMessage event, List<RefSpec> batch,
                                                 LiveGitProgressMonitor monitor, Long jobId) throws Exception {
        int attempts = Math.max(1, pushBatchRetries);
        Exception last = null;
        boolean refreshedAuth = false;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                throwIfStopRequested(jobId);
                CredentialsProvider creds = createCredentialsProvider(event, event.getTargetRepoUrl(), event.getTokenB());
                PushCommand pushCmd = git.push()
                        .setRemote("target")
                        .setCredentialsProvider(creds)
                        .setRefSpecs(batch)
                        .setForce(true)
                        .setProgressMonitor(monitor)
                        .setTimeout(httpTimeoutSeconds);

                Iterable<PushResult> pushResults = pushCmd.call();
                PushBatchResult parsed = parsePushResults(jobId, pushResults);
                if (parsed.authFailure && !refreshedAuth) {
                    refreshedAuth = true;
                    invalidateDestCredentials();
                    logAudit(jobId, LogLevel.WARN, "Destination returned 401/403; reminting credentials and retrying this batch...");
                    attempt--;
                    continue;
                }
                return parsed;
            } catch (Exception e) {
                last = e;
                rethrowIfStopRequested(jobId, e);
                String cleanErr = cleanRootCauseMessage(e);
                if (providerRateMeter != null && ProviderRateMeter.looksLikeGitThrottle(cleanErr)) {
                    providerRateMeter.recordGitHttpThrottle(cleanErr);
                }
                if (isAuthFailure(e) && !refreshedAuth) {
                    refreshedAuth = true;
                    invalidateDestCredentials();
                    logAudit(jobId, LogLevel.WARN, "Push batch authentication error: " + cleanErr
                            + ". Reminting destination credentials and retrying this batch...");
                    continue;
                }
                if (!isTransientTransportFailure(e) || attempt >= attempts) {
                    if (isAuthFailure(e)) {
                        PushBatchResult auth = new PushBatchResult();
                        auth.authFailure = true;
                        auth.rejectedMessages.add("Destination authentication failed: " + cleanErr);
                        auth.messages.add("Push to target failed: " + cleanErr);
                        return auth;
                    }
                    throw (e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(cleanErr, e));
                }
                long backoffMs = 2000L * attempt;
                logAudit(jobId, LogLevel.WARN, "Push batch transport error (attempt " + attempt + "/" + attempts
                        + "): " + cleanErr + ". Retrying same batch in " + backoffMs + "ms...");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throwIfStopRequested(jobId);
                    throw e;
                }
            }
        }
        throw last != null ? last : new IllegalStateException("Push batch failed with no exception");
    }

    private PushBatchResult parsePushResults(Long jobId, Iterable<PushResult> pushResults) {
        PushBatchResult result = new PushBatchResult();
        if (pushResults == null) {
            return result;
        }
        for (PushResult pr : pushResults) {
            for (RemoteRefUpdate rru : pr.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = rru.getStatus();
                String remoteMessage = rru.getMessage();
                String msg;
                if (rru.getSrcRef() == null) {
                    msg = "Pruned/Deleted ref " + rru.getRemoteName() + " [" + status + "]"
                            + (remoteMessage != null && !remoteMessage.isBlank() ? " " + remoteMessage : "");
                } else {
                    msg = "Ref " + rru.getSrcRef() + " -> " + rru.getRemoteName() + " [" + status + "]"
                            + (remoteMessage != null && !remoteMessage.isBlank() ? " " + remoteMessage : "");
                }
                result.messages.add(msg);
                if (isSuccessfulRemoteUpdate(status)) {
                    if (rru.getSrcRef() != null) {
                        result.successfulRefNames.add(rru.getSrcRef());
                    } else if (rru.getRemoteName() != null) {
                        result.deletedRemoteNames.add(rru.getRemoteName());
                    }
                } else {
                    result.destinationRejected = true;
                    result.rejectedMessages.add(msg);
                    logAudit(jobId, LogLevel.ERROR, msg);
                    if (remoteMessage != null && looksLikeUnauthorized(remoteMessage)) {
                        result.authFailure = true;
                    }
                }
            }
        }
        return result;
    }

    static boolean isSuccessfulRemoteUpdate(RemoteRefUpdate.Status status) {
        return status == RemoteRefUpdate.Status.OK
                || status == RemoteRefUpdate.Status.UP_TO_DATE
                || status == RemoteRefUpdate.Status.NON_EXISTING;
    }

    private void recordSuccessfulRefs(Git git, List<String> successfulRefNames, Map<String, String> dest,
                                     String targetRepoUrl, RepoMapping mapping, PairSide sourceSide) {
        if (successfulRefNames == null) {
            return;
        }
        Map<String, String> justPushed = new LinkedHashMap<>();
        for (String src : successfulRefNames) {
            if (src == null || src.contains("*")) continue;
            try {
                Ref ref = git.getRepository().exactRef(src);
                if (ref != null && ref.getObjectId() != null) {
                    String sha = ObjectId.toString(ref.getObjectId());
                    dest.put(src, sha);
                    justPushed.put(src, sha);
                    applyPushedRefToDestTracking(git, src, ref.getObjectId());
                    if (refOriginService != null && mapping != null && src.startsWith("refs/heads/")) {
                        refOriginService.recordIfAbsent(mapping.getId(), src, sourceSide);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not record pushed ref {}: {}", src, e.getMessage());
            }
        }
        recordShasOnLedger(dedupLedgerService, targetRepoUrl, justPushed);
    }

    /**
     * Local dest comparison uses {@code refs/remotes/target/*} from the pre-push inspect fetch.
     * After a successful push we already know the new dest tip — write it here so the pair
     * snapshot / silent post-job diff do not still show TARGET_MISSING until Refresh Diff.
     */
    static String destTrackingRefName(String srcOrDestRef) {
        if (srcOrDestRef == null || srcOrDestRef.isBlank()) {
            return null;
        }
        if (srcOrDestRef.startsWith("refs/heads/")) {
            return "refs/remotes/target/" + srcOrDestRef.substring("refs/heads/".length());
        }
        if (srcOrDestRef.startsWith("refs/tags/")) {
            return "refs/remotes/target-tags/" + srcOrDestRef.substring("refs/tags/".length());
        }
        return null;
    }

    static void applyPushedRefToDestTracking(Git git, String srcRef, ObjectId sha) {
        String tracking = destTrackingRefName(srcRef);
        if (git == null || tracking == null || sha == null) {
            return;
        }
        try {
            RefUpdate update = git.getRepository().updateRef(tracking);
            update.setNewObjectId(sha);
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            if (result == RefUpdate.Result.LOCK_FAILURE || result == RefUpdate.Result.IO_FAILURE) {
                log.debug("Dest tracking update for {} returned {}", tracking, result);
            }
        } catch (Exception e) {
            log.debug("Could not update dest tracking ref for {}: {}", srcRef, e.getMessage());
        }
    }

    static void deleteDestTrackingForRemoteRef(Git git, String destRef) {
        String tracking = destTrackingRefName(destRef);
        if (git == null || tracking == null) {
            return;
        }
        try {
            RefUpdate update = git.getRepository().updateRef(tracking);
            update.setForceUpdate(true);
            update.delete();
        } catch (Exception e) {
            log.debug("Could not delete dest tracking ref for {}: {}", destRef, e.getMessage());
        }
    }

    /**
     * Writes every successfully pushed tip SHA to the echo ledger immediately so dest
     * GitHub App push webhooks can be skipped before the rest of the job finishes.
     */
    static void recordShasOnLedger(DedupLedgerService ledger, String targetRepoUrl, Map<String, String> refToSha) {
        if (ledger == null || targetRepoUrl == null || refToSha == null || refToSha.isEmpty()) {
            return;
        }
        for (String sha : refToSha.values()) {
            if (sha != null && !sha.isBlank()) {
                ledger.recordSystemPush(targetRepoUrl, sha);
            }
        }
    }

    private void persistCompletedPushRefs(Long mappingId, Map<String, String> refs, JobStageProgress stageProgress) {
        if (stageProgress != null) {
            stageProgress.setCompletedPushRefs(refs == null || refs.isEmpty()
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(refs));
        }
        if (mappingId == null) {
            return;
        }
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setCompletedPushRefs(refs == null || refs.isEmpty() ? null : serializeCompletedPushRefs(refs));
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist completedPushRefs: {}", e.getMessage());
        }
    }

    private void persistTargetVisibility(Long mappingId, com.gitutility.model.enums.RepoVisibility visibility) {
        if (mappingId == null || visibility == null) return;
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setTargetVisibility(visibility);
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist targetVisibility: {}", e.getMessage());
        }
    }

    private void persistSourcePublicRead(Long mappingId, boolean publicRead) {
        if (mappingId == null) return;
        try {
            repoMappingRepository.findById(mappingId).ifPresent(mapping -> {
                mapping.setSourcePublicRead(publicRead);
                mapping.setSourceVisibility(publicRead
                        ? com.gitutility.model.enums.RepoVisibility.PUBLIC
                        : com.gitutility.model.enums.RepoVisibility.PRIVATE);
                repoMappingRepository.save(mapping);
            });
        } catch (Exception e) {
            log.debug("Could not persist sourcePublicRead cache: {}", e.getMessage());
        }
    }

    static Map<String, String> parseCompletedPushRefs(String blob) {
        Map<String, String> map = new LinkedHashMap<>();
        if (blob == null || blob.isBlank()) {
            return map;
        }
        for (String line : blob.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) continue;
            int eq = trimmed.indexOf('=');
            String ref = trimmed.substring(0, eq).trim();
            String sha = trimmed.substring(eq + 1).trim();
            if (!ref.isEmpty() && !sha.isEmpty()) {
                map.put(ref, sha);
            }
        }
        return map;
    }

    static String serializeCompletedPushRefs(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    static boolean isTransientTransportFailure(Throwable e) {
        String combined = flattenException(e).toLowerCase();
        return combined.contains("connection reset")
                || combined.contains("broken pipe")
                || combined.contains("connection refused")
                || combined.contains("unexpected end of file")
                || combined.contains("eofexception")
                || combined.contains("timed out")
                || combined.contains("timeout")
                || combined.contains("502")
                || combined.contains("503")
                || combined.contains("504")
                || combined.contains("goaway")
                || combined.contains("remote host terminated")
                || combined.contains("software caused connection abort");
    }

    public static String cleanRootCauseMessage(Throwable e) {
        if (e == null) return "Unknown error";
        List<String> messages = new ArrayList<>();
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                msg = msg.trim();
                if (!msg.equalsIgnoreCase("Exception caught during execution of push command")
                        && !msg.equalsIgnoreCase("Exception caught during execution of fetch command")
                        && !messages.contains(msg)) {
                    messages.add(msg);
                }
            }
        }
        if (messages.isEmpty()) {
            return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage().trim() : e.getClass().getSimpleName();
        }
        return String.join(": ", messages);
    }

    private static String flattenException(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(' ').append(t.getMessage());
            }
            if (t.getClass().getSimpleName() != null) {
                sb.append(' ').append(t.getClass().getSimpleName());
            }
        }
        return sb.toString();
    }

    private boolean isAuthFailure(Exception e) {
        String combined = flattenException(e).toLowerCase();
        return combined.contains("auth")
                || combined.contains("401")
                || combined.contains("403")
                || combined.contains("credentials")
                || combined.contains("not authorized")
                || combined.contains("permission")
                || combined.contains("access denied")
                || combined.contains("repository not found")
                || combined.contains("authentication is required")
                || combined.contains("transportexception");
    }

    private boolean localMirrorHasPacks(File repoDir) {
        if (repoDir == null) return false;
        File packDir = new File(repoDir, "objects/pack");
        File[] packs = packDir.listFiles((d, n) -> n != null && n.endsWith(".pack"));
        return packs != null && packs.length > 0;
    }

    private int countLocalRefs(Git git) {
        try {
            Repository repo = git.getRepository();
            return BareRepoHousekeeping.countPackedRefsWithPrefix(repo, "refs/heads/")
                    + BareRepoHousekeeping.countPackedRefsWithPrefix(repo, "refs/tags/")
                    + BareRepoHousekeeping.countPackedRefsWithPrefix(repo, "refs/notes/");
        } catch (Exception e) {
            return 0;
        }
    }

    private String describeBatch(List<RefSpec> batch) {
        List<String> names = new ArrayList<>();
        for (RefSpec spec : batch) {
            if (spec.getSource() != null) {
                names.add(spec.getSource());
            }
        }
        if (names.size() <= 4) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 3)) + ", +" + (names.size() - 3) + " more";
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static String formatTransferVolume(long gitWireBytes, long lfsBytes, int objectsReceived) {
        StringBuilder sb = new StringBuilder();
        if (gitWireBytes > 0) {
            sb.append(formatBytes(gitWireBytes)).append(" git wire");
        }
        if (lfsBytes > 0) {
            if (!sb.isEmpty()) {
                sb.append(" + ");
            }
            sb.append(formatBytes(lfsBytes)).append(" LFS");
        }
        if (objectsReceived > 0) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(objectsReceived).append(" objects");
        }
        return sb.isEmpty() ? "0 objects" : sb.toString();
    }

    private boolean isSimulationOrTestUrl(String url) {
        if (url == null) return true;
        String lower = url.toLowerCase();
        return lower.contains("example.com") || lower.contains("test.com") ||
                lower.contains("dummy") || lower.contains("localhost");
    }

    private void verifyDestinationWritable(SyncEventMessage event, RepoMapping mapping, Long jobId, SyncPipelineState pipeline) {
        if (isSimulationOrTestUrl(event.getTargetRepoUrl())) {
            pipeline.markSkipped(SyncPipelineState.VERIFY_DEST, "Simulated target");
            return;
        }
        if (scmProviderFacade == null) {
            pipeline.markDone(SyncPipelineState.VERIFY_DEST, "No SCM facade; skipping probe");
            return;
        }
        try {
            Long targetCredId = event.getTargetCredentialId();
            String destToken = resolveSideToken(event.getTokenB(), targetCredId);
            PermissionCheckReport report = scmProviderFacade.testConnection(null, TestConnectionRequest.builder()
                    .repoUrl(event.getTargetRepoUrl())
                    .token(destToken)
                    .credentialId(targetCredId)
                    .requiredAccess("WRITE")
                    .knownPrivate(mapping != null
                            && mapping.getTargetVisibility() == com.gitutility.model.enums.RepoVisibility.PRIVATE)
                    .build());
            boolean canWrite = report != null && report.isValid()
                    && report.getPermissions() != null
                    && report.getPermissions().isContentsWrite();
            if (!canWrite) {
                String errors = formatPermissionErrors(report);
                pipeline.markFailed(SyncPipelineState.VERIFY_DEST, errors);
                persistPipeline(jobId, pipeline, null);
                if (report != null && report.isPrivate()) {
                    persistTargetVisibility(event.getMappingId(), com.gitutility.model.enums.RepoVisibility.PRIVATE);
                }
                logAudit(jobId, LogLevel.ERROR, "Destination write preflight failed for "
                        + hostPathLabel(event.getTargetRepoUrl()) + ": " + errors);
                throw new IllegalStateException("Destination write preflight failed: " + errors);
            }
            pipeline.markDone(SyncPipelineState.VERIFY_DEST, "Contents write confirmed");
            persistPipeline(jobId, pipeline, null);
            persistTargetVisibility(event.getMappingId(), com.gitutility.model.enums.RepoVisibility.PRIVATE);
            logAudit(jobId, LogLevel.INFO, "Destination write preflight passed for " + hostPathLabel(event.getTargetRepoUrl()) + ".");
            if (actionsTriggerSuppressionService != null) {
                try (com.gitutility.service.ScmCredentialContext.Scope ignored =
                             com.gitutility.service.ScmCredentialContext.open(targetCredId)) {
                    actionsTriggerSuppressionService.validateWriteAuthOrThrow(
                            event.getTargetRepoUrl(), destToken);
                }
                if (actionsTriggerSuppressionService.isEnabled()
                        && actionsTriggerSuppressionService.supportsRepo(event.getTargetRepoUrl())) {
                    String bot = actionsTriggerSuppressionService.resolveBotLogin(event.getTargetRepoUrl());
                    logAudit(jobId, LogLevel.INFO, "Mirror Actions suppression active for destination writes"
                            + (bot != null ? " (actor=" + bot + ")" : "") + ".");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            pipeline.markFailed(SyncPipelineState.VERIFY_DEST, e.getMessage());
            persistPipeline(jobId, pipeline, null);
            logAudit(jobId, LogLevel.ERROR, "Destination write preflight failed: " + e.getMessage());
            throw new IllegalStateException("Destination write preflight failed: " + e.getMessage(), e);
        }
    }

    private static String formatPermissionErrors(PermissionCheckReport report) {
        if (report == null) {
            return "no permission report";
        }
        if (report.getErrors() != null && !report.getErrors().isEmpty()) {
            return String.join("; ", report.getErrors());
        }
        if (report.getMessage() != null && !report.getMessage().isBlank()) {
            return report.getMessage();
        }
        return "destination Contents write is not granted";
    }

    void logJobAudit(Long jobId, LogLevel level, String message) {
        logAudit(jobId, level, message);
    }

    private void broadcastPipeline(Long jobId, Long mappingId, SyncPipelineState pipeline) {
        if (webSocketNotificationService == null || jobId == null || pipeline == null) {
            return;
        }
        webSocketNotificationService.notifyPipeline(
                jobId,
                mappingId,
                pipeline.getCurrentStageId(),
                pipeline.currentLabel(),
                pipeline.toMap(),
                trafficSnapshot()
        );
    }

    private void persistPipeline(Long jobId, SyncPipelineState pipeline, String rejectedPushRefs) {
        if (jobId == null || syncJobRepository == null) {
            return;
        }
        try {
            JobStageProgress stageProgress = activeStageProgress.get();
            syncJobRepository.findById(jobId).ifPresent(job -> {
                if (pipeline != null) {
                    job.setPipelineJson(pipeline.toJson());
                    job.setResumeStageId(jobExecutionStateService.resolveResumeStageId(pipeline));
                }
                if (stageProgress != null) {
                    job.setStageProgressJson(stageProgress.toJson());
                }
                if (rejectedPushRefs != null) {
                    job.setRejectedPushRefs(rejectedPushRefs);
                }
                if (providerRateMeter != null) {
                    providerRateMeter.copyTo(job);
                }
                syncJobRepository.save(job);
                broadcastPipeline(jobId, job.getMappingId() != null ? job.getMappingId() : null, pipeline);
            });
        } catch (Exception e) {
            log.debug("Could not persist pipeline telemetry: {}", e.getMessage());
        }
    }

    private Map<String, Object> trafficSnapshot() {
        return providerRateMeter != null ? providerRateMeter.snapshotMap() : Map.of();
    }

    private void touchRunningDuration(Long jobId) {
        if (syncJobService != null && jobId != null) {
            syncJobService.touchRunningDuration(jobId);
        }
    }

    private void invalidateDestCredentials() {
        if (scmProviderFacade != null) {
            try {
                scmProviderFacade.invalidateAllCaches();
            } catch (Exception e) {
                log.debug("Could not invalidate SCM credential caches: {}", e.getMessage());
            }
        }
    }

    private static String firstRejectedSummary(PushBatchResult batchResult) {
        if (batchResult == null || batchResult.rejectedMessages.isEmpty()) {
            return "Destination rejected ref";
        }
        return batchResult.rejectedMessages.get(0);
    }

    private static String statusOf(SyncPipelineState pipeline, String stageId) {
        if (pipeline == null) {
            return null;
        }
        for (SyncPipelineState.Stage stage : pipeline.getStages()) {
            if (stageId.equals(stage.id)) {
                return stage.status;
            }
        }
        return null;
    }

    static boolean looksLikeUnauthorized(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("401") || lower.contains("unauthorized") || lower.contains("403")
                || lower.contains("authentication") || lower.contains("bad credentials");
    }

    static class PushBatchResult {
        List<String> messages = new ArrayList<>();
        List<String> successfulRefNames = new ArrayList<>();
        List<String> deletedRemoteNames = new ArrayList<>();
        List<String> rejectedMessages = new ArrayList<>();
        boolean destinationRejected;
        boolean authFailure;
    }

    private static final class ThrottledAuditProgress {
        private final java.util.function.Consumer<String> sink;
        private final long intervalMs;
        private long lastEmitMs;

        ThrottledAuditProgress(java.util.function.Consumer<String> sink, long intervalMs) {
            this.sink = sink;
            this.intervalMs = Math.max(500L, intervalMs);
        }

        void maybeEmit(String message) {
            long now = System.currentTimeMillis();
            if (now - lastEmitMs >= intervalMs) {
                lastEmitMs = now;
                sink.accept(message);
            }
        }

        void forceEmit(String message) {
            lastEmitMs = System.currentTimeMillis();
            sink.accept(message);
        }
    }

    private void logAudit(Long jobId, LogLevel level, String message) {
        if (jobId == null) return;
        if (level == null) {
            level = LogLevel.INFO;
        }
        switch (level) {
            case DEBUG -> log.debug("[job-{}] {}", jobId, message);
            case WARN -> log.warn("[job-{}] {}", jobId, message);
            case ERROR -> log.error("[job-{}] {}", jobId, message);
            default -> log.info("[job-{}] {}", jobId, message);
        }
        try {
            SyncAuditLog logEntry = SyncAuditLog.builder()
                    .jobId(jobId)
                    .level(level)
                    .message(message)
                    .timestamp(new Date().toInstant())
                    .build();
            auditLogRepository.save(logEntry);

            if (enterpriseLoggingService != null) {
                enterpriseLoggingService.shipAuditLog(logEntry);
            }
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage());
        }
    }

    static String hostPathLabel(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        try {
            String cleaned = url.replaceAll("://[^/]*@", "://");
            java.net.URI uri = java.net.URI.create(cleaned);
            String host = uri.getHost() != null ? uri.getHost() : "";
            String path = uri.getPath() != null
                    ? uri.getPath().replaceAll("^/", "").replaceAll("\\.git$", "")
                    : "";
            if (host.isEmpty()) {
                return maskUrl(url);
            }
            return path.isEmpty() ? host : host + "/" + path;
        } catch (Exception e) {
            return maskUrl(url);
        }
    }

    private void throwIfStopRequested(Long jobId) {
        if (isPauseRequested(jobId)) {
            logAudit(jobId, LogLevel.INFO, "Sync paused by operator — checkpoint preserved.");
            throw new JobPausedException(jobId);
        }
        if (isCancelRequested(jobId)) {
            logAudit(jobId, LogLevel.WARN, "Sync cancelled by operator.");
            throw new JobCancelledException(jobId);
        }
    }

    private boolean isStopRequested(Long jobId) {
        if (isPauseRequested(jobId)) {
            logAudit(jobId, LogLevel.INFO, "Sync paused by operator — checkpoint preserved.");
            throw new JobPausedException(jobId);
        }
        return isCancelRequested(jobId);
    }

    private boolean isCancelRequested(Long jobId) {
        return jobId != null && jobCancellationService != null && jobCancellationService.isCancelRequested(jobId);
    }

    private boolean isPauseRequested(Long jobId) {
        return jobId != null && jobCancellationService != null && jobCancellationService.isPauseRequested(jobId);
    }

    private void rethrowIfStopRequested(Long jobId, Exception e) {
        if (e instanceof JobPausedException jpe) {
            throw jpe;
        }
        if (e instanceof JobCancelledException jce) {
            throw jce;
        }
        if (isPauseRequested(jobId)) {
            throw new JobPausedException(jobId);
        }
        if (e instanceof org.eclipse.jgit.api.errors.CanceledException
                || (e instanceof InterruptedException)
                || isCancelRequested(jobId)) {
            throw new JobCancelledException(jobId);
        }
    }

    private static String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("://([^:]+):([^@]+)@", "://$1:***@");
    }
}
