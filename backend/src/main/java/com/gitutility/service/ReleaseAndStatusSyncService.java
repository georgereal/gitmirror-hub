package com.gitutility.service;

import com.gitutility.model.dto.CiCheckPage;
import com.gitutility.model.dto.CommitStatusDetail;
import com.gitutility.model.dto.ReleaseListPage;
import com.gitutility.model.dto.ReleaseLookup;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.LogLevel;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.repository.SyncJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Bidirectional metadata replication between mirror pair sides:
 * <ul>
 *   <li><b>Releases</b> — cursor-paged source listing, idempotent create/update on the
 *       destination, binary asset streaming (source download → temp file → destination upload).</li>
 *   <li><b>CI checks</b> — check runs + legacy commit statuses backfilled onto mirrored tip
 *       commits (native check runs on GitHub/GHES; build statuses on GitLab/Bitbucket).</li>
 * </ul>
 * All provider calls bind the pair-side {@link ScmCredentialContext} (credential + App
 * installation) so the correct token is minted for each side; hard auth failures throw
 * {@link MetadataSyncException} instead of being swallowed.
 */
@Service
@Slf4j
public class ReleaseAndStatusSyncService {

    /** Hard failure (auth, missing credentials, provider rejection) that must fail the job stage loudly. */
    public static class MetadataSyncException extends RuntimeException {
        public MetadataSyncException(String message) {
            super(message);
        }

        public MetadataSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record ReleaseSyncResult(
            long sourceCount,
            long targetCountBefore,
            long targetCountAfter,
            int created,
            int updated,
            int unchanged,
            int assetsUploaded,
            int failed,
            boolean providerSupported,
            List<String> errors) {
        public int mirroredCount() {
            return created + updated;
        }
    }

    public record CiCheckSyncResult(
            int tipsInspected,
            int checkRunsFound,
            int checkRunsReplicated,
            int checkRunsSkipped,
            int statusesReplicated,
            boolean nativeCheckRunsSupported,
            List<String> errors) {
    }

    public record ReleaseSyncRequest(
            Long mappingId,
            String sourceRepoUrl,
            String targetRepoUrl,
            Long sourceCredentialId,
            String sourceInstallationId,
            Long targetCredentialId,
            String targetInstallationId,
            Long jobId,
            Consumer<String> progress) {
    }

    public record CiCheckSyncRequest(
            Long mappingId,
            String sourceRepoUrl,
            String targetRepoUrl,
            Long sourceCredentialId,
            String sourceInstallationId,
            Long targetCredentialId,
            String targetInstallationId,
            Long jobId,
            List<String> tipShas,
            Consumer<String> progress) {
    }

    private final ScmProviderFacade scmProviderFacade;
    private final RepoMappingRepository repoMappingRepository;
    private final SyncJobRepository syncJobRepository;
    private final SyncAuditLogRepository syncAuditLogRepository;
    private final ScmCredentialService scmCredentialService;
    private final PairDiffSnapshotService pairDiffSnapshotService;
    private final PairCatchupLedger pairCatchupLedger;
    private final WebSocketNotificationService webSocketNotificationService;
    private final StorageTieringService storageTieringService;
    private final ExecutorService releaseSyncExecutor;
    private final Executor syncTaskExecutor;

    @Value("${git-utility.git.release-page-size:50}")
    private int releasePageSize;

    @Value("${git-utility.git.ci-check-tip-limit:8}")
    private int ciCheckTipLimit;

    public ReleaseAndStatusSyncService(
            ScmProviderFacade scmProviderFacade,
            RepoMappingRepository repoMappingRepository,
            SyncJobRepository syncJobRepository,
            SyncAuditLogRepository syncAuditLogRepository,
            ScmCredentialService scmCredentialService,
            PairDiffSnapshotService pairDiffSnapshotService,
            PairCatchupLedger pairCatchupLedger,
            WebSocketNotificationService webSocketNotificationService,
            StorageTieringService storageTieringService,
            @Qualifier("releaseSyncExecutor") ExecutorService releaseSyncExecutor,
            @Qualifier("syncTaskExecutor") Executor syncTaskExecutor) {
        this.scmProviderFacade = scmProviderFacade;
        this.repoMappingRepository = repoMappingRepository;
        this.syncJobRepository = syncJobRepository;
        this.syncAuditLogRepository = syncAuditLogRepository;
        this.scmCredentialService = scmCredentialService;
        this.pairDiffSnapshotService = pairDiffSnapshotService;
        this.pairCatchupLedger = pairCatchupLedger;
        this.webSocketNotificationService = webSocketNotificationService;
        this.storageTieringService = storageTieringService;
        this.releaseSyncExecutor = releaseSyncExecutor;
        this.syncTaskExecutor = syncTaskExecutor;
    }

    private void report(Consumer<String> progress, String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }

    /**
     * Replicates an external CI/CD commit status onto the destination repository for a commit SHA,
     * binding the pair's target credential + installation before delegating to the adapter.
     */
    public boolean replicateCommitStatus(RepoMapping mapping, String sha, String state, String targetUrl, String description, String context) {
        if (mapping == null || sha == null || sha.isBlank()) return false;

        String targetFullName = scmProviderFacade.parseRepoFullName(mapping.getRepoBUrl());
        if (targetFullName == null) return false;

        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(mapping.getRepoBUrl());
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                mapping.getTargetCredentialId(), mapping.getTargetInstallationId())) {
            return targetAdapter.replicateCommitStatus(targetFullName, sha, state, targetUrl, description, context);
        }
    }

    /** Legacy count-only entry point (kept for callers and tests). */
    public int syncReleases(Long mappingId, String sourceRepoUrl, String targetRepoUrl) {
        return syncReleases(mappingId, sourceRepoUrl, targetRepoUrl, null);
    }

    /** Legacy entry point returning the mirrored (created + updated) release count. */
    public int syncReleases(Long mappingId, String sourceRepoUrl, String targetRepoUrl, Consumer<String> progress) {
        ReleaseSyncRequest request = requestFromMapping(mappingId, sourceRepoUrl, targetRepoUrl, progress);
        return syncReleases(request).mirroredCount();
    }

    private ReleaseSyncRequest requestFromMapping(Long mappingId, String sourceRepoUrl, String targetRepoUrl, Consumer<String> progress) {
        RepoMapping mapping = mappingId != null ? repoMappingRepository.findById(mappingId).orElse(null) : null;
        return new ReleaseSyncRequest(
                mappingId,
                sourceRepoUrl,
                targetRepoUrl,
                mapping != null ? mapping.getSourceCredentialId() : null,
                mapping != null ? mapping.getSourceInstallationId() : null,
                mapping != null ? mapping.getTargetCredentialId() : null,
                mapping != null ? mapping.getTargetInstallationId() : null,
                null,
                progress);
    }

    /**
     * Full release mirror: cursor-paged listing on both sides (in parallel), idempotent
     * create/update of missing or drifted releases on the destination, and binary asset
     * streaming for assets absent on the destination. Per-release work fans out onto a
     * bounded pool. Hard auth failures throw {@link MetadataSyncException}.
     */
    public ReleaseSyncResult syncReleases(ReleaseSyncRequest request) {
        String sourceFullName = scmProviderFacade.parseRepoFullName(request.sourceRepoUrl());
        String targetFullName = scmProviderFacade.parseRepoFullName(request.targetRepoUrl());
        if (sourceFullName == null || targetFullName == null) {
            throw new MetadataSyncException("Cannot resolve owner/repo names for release sync (source="
                    + request.sourceRepoUrl() + ", target=" + request.targetRepoUrl() + ")");
        }

        ScmProviderAdapter sourceAdapter = scmProviderFacade.getAdapterForUrl(request.sourceRepoUrl());
        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(request.targetRepoUrl());
        Consumer<String> progress = request.progress();
        List<String> errors = new ArrayList<>();

        // Destination writes are never anonymous — the target side must hold a usable credential.
        String targetToken = requireSideToken("target", request.targetCredentialId(), request.targetInstallationId());
        if (targetToken == null || targetToken.isBlank()) {
            throw new MetadataSyncException("No usable destination credential for " + targetFullName
                    + ". Bind a destination SCM credential on the pair before syncing releases.");
        }

        if (!targetAdapter.supportsReleaseSync()) {
            report(progress, "Releases · " + targetAdapter.getProviderType()
                    + " has no Releases API — tags mirror via Git; nothing to replicate");
            List<SyncDiffReport.ReleaseDetail> sourceOnly = listAllReleases(
                    sourceAdapter, sourceFullName, request.sourceCredentialId(), request.sourceInstallationId(), null);
            return new ReleaseSyncResult(sourceOnly.size(), 0, 0, 0, 0, 0, 0, 0, false,
                    List.of("Destination provider " + targetAdapter.getProviderType() + " does not support release creation"));
        }

        // 1. List both sides concurrently (independent cursor chains, each binding its own credentials).
        report(progress, "Releases · Listing releases on " + sourceFullName + " and " + targetFullName + "…");
        Future<List<SyncDiffReport.ReleaseDetail>> sourceFuture = releaseSyncExecutor.submit(() ->
                listAllReleases(sourceAdapter, sourceFullName,
                        request.sourceCredentialId(), request.sourceInstallationId(), null));
        Future<Map<String, SyncDiffReport.ReleaseDetail>> targetFuture = releaseSyncExecutor.submit(() ->
                listTargetReleasesByTag(targetAdapter, targetFullName,
                        request.targetCredentialId(), request.targetInstallationId()));

        List<SyncDiffReport.ReleaseDetail> sourceReleases;
        Map<String, SyncDiffReport.ReleaseDetail> targetByTag;
        try {
            sourceReleases = sourceFuture.get();
            targetByTag = targetFuture.get();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof MetadataSyncException mse) {
                throw mse;
            }
            throw new MetadataSyncException("Release listing failed: " + cause.getMessage(), cause);
        }

        long targetCountBefore = targetByTag.size();
        report(progress, "Releases · Source has " + sourceReleases.size()
                + " release(s); destination has " + targetCountBefore);

        // 2. Plan per-release work items (CREATE / UPDATE / UNCHANGED) and fan out.
        int[] counters = executeReleasePlan(request, sourceAdapter, sourceFullName,
                targetAdapter, targetFullName, sourceReleases, targetByTag, progress, errors);
        int created = counters[0];
        int updated = counters[1];
        int unchanged = counters[2];
        int assetsUploaded = counters[3];
        int failed = counters[4];

        long targetCountAfter = Math.max(targetCountBefore, targetCountBefore + created);
        report(progress, "Releases · Done: " + created + " created, " + updated + " updated, "
                + unchanged + " unchanged, " + assetsUploaded + " asset(s) streamed"
                + (failed > 0 ? ", " + failed + " failed" : ""));

        ReleaseSyncResult result = new ReleaseSyncResult(
                sourceReleases.size(), targetCountBefore, targetCountAfter,
                created, updated, unchanged, assetsUploaded, failed, true, List.copyOf(errors));

        if (request.mappingId() != null) {
            try {
                pairDiffSnapshotService.updateReleases(request.mappingId(),
                        (int) Math.min(Integer.MAX_VALUE, result.sourceCount()),
                        (int) Math.min(Integer.MAX_VALUE, result.targetCountAfter()));
            } catch (Exception e) {
                log.debug("Could not persist release snapshot for mapping #{}: {}", request.mappingId(), e.getMessage());
            }
        }
        return result;
    }

    /** Builds per-release work items and runs them on the bounded release pool. */
    private int[] executeReleasePlan(ReleaseSyncRequest request,
                                     ScmProviderAdapter sourceAdapter,
                                     String sourceFullName,
                                     ScmProviderAdapter targetAdapter,
                                     String targetFullName,
                                     List<SyncDiffReport.ReleaseDetail> sourceReleases,
                                     Map<String, SyncDiffReport.ReleaseDetail> targetByTag,
                                     Consumer<String> progress,
                                     List<String> errors) {
        int unchanged = 0;

        List<Callable<WorkOutcome>> tasks = new ArrayList<>();
        for (SyncDiffReport.ReleaseDetail release : sourceReleases) {
            if (release == null || release.getTagName() == null || release.getTagName().isBlank()) {
                continue;
            }
            SyncDiffReport.ReleaseDetail existing = targetByTag.get(release.getTagName());
            String tag = release.getTagName();
            String label = release.getName() != null && !release.getName().isBlank() ? release.getName() : tag;
            String externalId = existing != null && existing.getId() != null && existing.getId() > 0
                    ? String.valueOf(existing.getId())
                    : null;

            boolean needsCreate = existing == null;
            boolean needsMetadataUpdate = !needsCreate && releaseDrifted(release, existing);
            List<SyncDiffReport.ReleaseAssetDetail> missingAssets = missingAssetsFor(release, existing);
            if (!(needsCreate || needsMetadataUpdate || !missingAssets.isEmpty())) {
                unchanged++;
                continue;
            }

            final SyncDiffReport.ReleaseDetail rel = release;
            final String extId = externalId;
            final boolean create = needsCreate;
            final boolean metaUpdate = needsMetadataUpdate;
            final List<SyncDiffReport.ReleaseAssetDetail> assets = missingAssets;
            tasks.add(() -> {
                String releaseExtId = extId;
                if (create) {
                    report(progress, "Releases · Creating '" + label + "' on " + targetFullName + "…");
                    try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                            request.targetCredentialId(), request.targetInstallationId())) {
                        releaseExtId = targetAdapter.createRelease(
                                targetFullName, tag, rel.getName(), rel.getBody(), rel.isDraft(), rel.isPrerelease());
                    }
                    if (releaseExtId == null || releaseExtId.isBlank()) {
                        throw new MetadataSyncException("Destination did not return a release id for tag " + tag);
                    }
                } else if (metaUpdate) {
                    report(progress, "Releases · Updating '" + label + "' on " + targetFullName + "…");
                    try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                            request.targetCredentialId(), request.targetInstallationId())) {
                        targetAdapter.updateRelease(targetFullName, extId, tag, rel.getName(), rel.getBody(),
                                rel.isDraft(), rel.isPrerelease());
                    }
                }
                int uploaded = transferReleaseAssets(request, sourceAdapter, sourceFullName,
                        targetAdapter, targetFullName, releaseExtId, tag, label, assets, progress);
                return new WorkOutcome(create, metaUpdate, uploaded);
            });
        }
        // {created, updated, unchanged, assetsUploaded, failed}
        return collectReleaseOutcomes(tasks, progress, errors, unchanged);
    }

    /** Runs the queued release tasks on the bounded pool and tallies their outcomes. */
    private int[] collectReleaseOutcomes(List<Callable<WorkOutcome>> tasks,
                                         Consumer<String> progress,
                                         List<String> errors,
                                         int unchanged) {
        int created = 0;
        int updated = 0;
        int assetsUploaded = 0;
        int failed = 0;
        if (!tasks.isEmpty()) {
            String first = tasks.size() + " release(s)";
            report(progress, "Releases · Mirroring " + first + " to the destination…");
        }
        try {
            List<Future<WorkOutcome>> futures = tasks.isEmpty()
                    ? List.of()
                    : releaseSyncExecutor.invokeAll(tasks);
            for (Future<WorkOutcome> future : futures) {
                try {
                    WorkOutcome outcome = future.get();
                    if (outcome.created()) {
                        created++;
                    } else if (outcome.updated()) {
                        updated++;
                    } else if (outcome.assetsUploaded() > 0) {
                        // asset-only touch keeps the release as already-present
                    }
                    assetsUploaded += outcome.assetsUploaded();
                } catch (java.util.concurrent.ExecutionException ee) {
                    failed++;
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    if (isAuthFailure(message)) {
                        throw new MetadataSyncException("Release sync aborted — destination credential rejected: " + message, cause);
                    }
                    errors.add(message);
                    log.warn("Release sync item failure: {}", message);
                    report(progress, "Releases · Item failed: " + message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetadataSyncException("Release sync interrupted", e);
        }
        return new int[]{created, updated, unchanged, assetsUploaded, failed};
    }

    private record WorkOutcome(boolean created, boolean updated, int assetsUploaded) {
    }

    /** Streams missing assets source → temp file → destination; each side binds its own credentials. */
    private int transferReleaseAssets(ReleaseSyncRequest request,
                                      ScmProviderAdapter sourceAdapter,
                                      String sourceFullName,
                                      ScmProviderAdapter targetAdapter,
                                      String targetFullName,
                                      String releaseExternalId,
                                      String tag,
                                      String label,
                                      List<SyncDiffReport.ReleaseAssetDetail> assets,
                                      Consumer<String> progress) {
        if (assets == null || assets.isEmpty() || releaseExternalId == null || releaseExternalId.isBlank()) {
            return 0;
        }
        int uploaded = 0;
        for (SyncDiffReport.ReleaseAssetDetail asset : assets) {
            if (asset == null || asset.getName() == null || asset.getName().isBlank()) {
                continue;
            }
            File tempFile = null;
            try {
                report(progress, "Releases · Streaming asset '" + asset.getName() + "' ("
                        + asset.getFormattedSize() + ") for '" + label + "'…");
                try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                        request.sourceCredentialId(), request.sourceInstallationId())) {
                    tempFile = sourceAdapter.downloadReleaseAsset(sourceFullName, asset.getDownloadUrl(), asset.getName());
                }
                if (tempFile == null || tempFile.length() == 0) {
                    throw new MetadataSyncException("Source asset '" + asset.getName() + "' could not be downloaded (no bytes).");
                }
                try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                        request.targetCredentialId(), request.targetInstallationId())) {
                    boolean ok = targetAdapter.uploadReleaseAsset(
                            targetFullName, releaseExternalId, tag, asset.getName(),
                            asset.getContentType() != null ? asset.getContentType() : "application/octet-stream",
                            tempFile);
                    if (!ok) {
                        throw new MetadataSyncException("Destination rejected asset '" + asset.getName() + "' for release " + tag);
                    }
                }
                uploaded++;
            } finally {
                if (tempFile != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(tempFile.toPath());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return uploaded;
    }

    /** True when the destination release is missing metadata present on the source release. */
    private boolean releaseDrifted(SyncDiffReport.ReleaseDetail source, SyncDiffReport.ReleaseDetail target) {
        if (target == null) {
            return true;
        }
        String sourceName = source.getName() != null ? source.getName() : "";
        String targetName = target.getName() != null ? target.getName() : "";
        if (!sourceName.equals(targetName)) {
            return true;
        }
        String sourceBody = source.getBody() != null ? source.getBody() : "";
        String targetBody = target.getBody() != null ? target.getBody() : "";
        if (!sourceBody.equals(targetBody)) {
            return true;
        }
        return source.isDraft() != target.isDraft() || source.isPrerelease() != target.isPrerelease();
    }

    private List<SyncDiffReport.ReleaseAssetDetail> missingAssetsFor(SyncDiffReport.ReleaseDetail source,
                                                                    SyncDiffReport.ReleaseDetail target) {
        if (source.getAssets() == null || source.getAssets().isEmpty()) {
            return List.of();
        }
        Set<String> present = new LinkedHashSet<>();
        if (target != null && target.getAssets() != null) {
            for (SyncDiffReport.ReleaseAssetDetail asset : target.getAssets()) {
                if (asset != null && asset.getName() != null) {
                    present.add(asset.getName());
                }
            }
        }
        List<SyncDiffReport.ReleaseAssetDetail> missing = new ArrayList<>();
        for (SyncDiffReport.ReleaseAssetDetail asset : source.getAssets()) {
            if (asset != null && asset.getName() != null && !present.contains(asset.getName())) {
                missing.add(asset);
            }
        }
        return missing;
    }

    /** Cursor loop over {@code listReleasesPage}; each page binds the side's credentials. */
    private List<SyncDiffReport.ReleaseDetail> listAllReleases(ScmProviderAdapter adapter,
                                                               String repoFullName,
                                                               Long credentialId,
                                                               String installationId,
                                                               Consumer<String> progress) {
        List<SyncDiffReport.ReleaseDetail> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (pages < 200) {
            ReleaseListPage page;
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credentialId, installationId)) {
                page = adapter.listReleasesPage(repoFullName, cursor, releasePageSize);
            } catch (MetadataSyncException e) {
                throw e;
            } catch (Exception e) {
                throw new MetadataSyncException("Release listing failed on " + adapter.getProviderType()
                        + " for " + repoFullName + ": " + e.getMessage(), e);
            }
            if (page == null) {
                break;
            }
            if (page.items() != null) {
                all.addAll(page.items());
            }
            pages++;
            if (progress != null) {
                progress.accept("Releases · " + repoFullName + ": " + all.size() + " release(s) listed"
                        + (pages > 1 ? " (" + pages + " pages)" : ""));
            }
            if (!page.hasNextPage() || page.nextCursor() == null || page.nextCursor().isBlank()) {
                break;
            }
            cursor = page.nextCursor();
        }
        return all;
    }

    /** Destination-side release listing keyed by tag for idempotent diffs. */
    private Map<String, SyncDiffReport.ReleaseDetail> listTargetReleasesByTag(ScmProviderAdapter adapter,
                                                                              String repoFullName,
                                                                              Long credentialId,
                                                                              String installationId) {
        Map<String, SyncDiffReport.ReleaseDetail> byTag = new LinkedHashMap<>();
        for (SyncDiffReport.ReleaseDetail release : listAllReleases(adapter, repoFullName, credentialId, installationId, null)) {
            if (release != null && release.getTagName() != null && !release.getTagName().isBlank()) {
                byTag.putIfAbsent(release.getTagName(), release);
            }
        }
        return byTag;
    }

    /**
     * Resolves the side token for validation through the credential + installation binding so
     * multi-install GitHub Apps mint the install that owns the repository.
     */
    private String requireSideToken(String side, Long credentialId, String installationId) {
        if (credentialId == null) {
            return null; // anonymous / public-read side is allowed only for source
        }
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credentialId, installationId)) {
            return scmCredentialService.resolveCurrentOrNull();
        } catch (Exception e) {
            throw new MetadataSyncException("Cannot resolve " + side + " credential #" + credentialId + ": " + e.getMessage(), e);
        }
    }

    private boolean isAuthFailure(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("401") || lower.contains("403")
                || lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("bad credentials") || lower.contains("installation_mismatch");
    }

    // ------------------------------------------------------------------
    // CI check runs + commit statuses sync
    // ------------------------------------------------------------------

    /**
     * Backfills CI check runs and commit statuses from the source onto mirrored destination
     * commits. Tips default to the most recently committed heads in the local bare mirror
     * (trunk first, capped at {@code ciCheckTipLimit}). Idempotent: a check run with the same
     * name and conclusion on the destination is skipped.
     */
    public CiCheckSyncResult syncCiChecks(CiCheckSyncRequest request) {
        String sourceFullName = scmProviderFacade.parseRepoFullName(request.sourceRepoUrl());
        String targetFullName = scmProviderFacade.parseRepoFullName(request.targetRepoUrl());
        if (sourceFullName == null || targetFullName == null) {
            throw new MetadataSyncException("Cannot resolve owner/repo names for CI check sync");
        }
        ScmProviderAdapter sourceAdapter = scmProviderFacade.getAdapterForUrl(request.sourceRepoUrl());
        ScmProviderAdapter targetAdapter = scmProviderFacade.getAdapterForUrl(request.targetRepoUrl());
        Consumer<String> progress = request.progress();

        List<String> errors = new ArrayList<>();
        boolean nativeCheckRuns = targetAdapter.supportsCheckRunSync();

        List<String> tips = request.tipShas() != null && !request.tipShas().isEmpty()
                ? new ArrayList<>(request.tipShas())
                : resolveTipShas(request.mappingId());
        if (tips.isEmpty()) {
            report(progress, "CI checks · No mirrored tip commits found — run a Git sync first");
            return new CiCheckSyncResult(0, 0, 0, 0, 0, nativeCheckRuns, errors);
        }

        if (!nativeCheckRuns) {
            report(progress, "CI checks · " + targetAdapter.getProviderType()
                    + " has no check-runs API — checks mirror as commit/build statuses");
        }

        report(progress, "CI checks · Inspecting " + tips.size() + " tip commit(s)…");
        int checkRunsFound = 0;
        int checkRunsReplicated = 0;
        int checkRunsSkipped = 0;
        int statusesReplicated = 0;

        for (String tip : tips) {
            String sha = tip == null ? null : tip.trim();
            if (sha == null || sha.length() < 7) {
                continue;
            }
            String shortSha = sha.substring(0, Math.min(sha.length(), 7));
            report(progress, "CI checks · Commit " + shortSha + "…");

            // Source-side listing (bound to source credentials).
            List<SyncDiffReport.CiCheckRunDetail> sourceRuns;
            List<CommitStatusDetail> sourceStatuses;
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                    request.sourceCredentialId(), request.sourceInstallationId())) {
                sourceRuns = listAllCheckRuns(sourceAdapter, sourceFullName, sha);
                sourceStatuses = sourceAdapter.listCommitStatuses(sourceFullName, sha);
            } catch (Exception e) {
                errors.add("Source listing failed for " + shortSha + ": " + e.getMessage());
                continue;
            }

            // Destination-side listing for idempotency (bound to target credentials).
            Map<String, String> targetRunKeyToConclusion = new LinkedHashMap<>();
            Map<String, String> targetStatusStateByContext = new LinkedHashMap<>();
            try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                    request.targetCredentialId(), request.targetInstallationId())) {
                if (nativeCheckRuns) {
                    for (SyncDiffReport.CiCheckRunDetail run : listAllCheckRuns(targetAdapter, targetFullName, sha)) {
                        if (run != null && run.getName() != null) {
                            targetRunKeyToConclusion.putIfAbsent(run.getName(),
                                    run.getConclusion() != null ? run.getConclusion() : run.getStatus());
                        }
                    }
                }
                for (CommitStatusDetail status : targetAdapter.listCommitStatuses(targetFullName, sha)) {
                    if (status != null && status.context() != null) {
                        targetStatusStateByContext.putIfAbsent(status.context(), status.state());
                    }
                }
            } catch (Exception e) {
                errors.add("Destination listing failed for " + shortSha + ": " + e.getMessage());
            }

            if (nativeCheckRuns) {
                for (SyncDiffReport.CiCheckRunDetail run : sourceRuns) {
                    if (run == null || run.getName() == null || run.getName().isBlank()) {
                        continue;
                    }
                    checkRunsFound++;
                    String sourceKey = run.getConclusion() != null && !run.getConclusion().isBlank()
                            ? run.getConclusion()
                            : run.getStatus();
                    String existing = targetRunKeyToConclusion.get(run.getName());
                    if (sourceKey != null && sourceKey.equalsIgnoreCase(existing)) {
                        checkRunsSkipped++;
                        continue;
                    }
                    try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                            request.targetCredentialId(), request.targetInstallationId())) {
                        targetAdapter.createCheckRun(targetFullName, sha, run.getName(),
                                run.getStatus(), run.getConclusion(), run.getStartedAt(), run.getCompletedAt(),
                                run.getHtmlUrl(), "Mirrored from " + sourceFullName + " commit " + shortSha);
                    } catch (Exception e) {
                        String message = "Check run '" + run.getName() + "' on " + shortSha + ": " + e.getMessage();
                        if (isAuthFailure(message)) {
                            throw new MetadataSyncException("CI check sync aborted — destination credential rejected: " + message, e);
                        }
                        errors.add(message);
                        continue;
                    }
                    checkRunsReplicated++;
                }
            }

            // Legacy commit statuses: replicate the newest state per context when it differs.
            Map<String, CommitStatusDetail> latestByContext = new LinkedHashMap<>();
            for (CommitStatusDetail status : sourceStatuses) {
                if (status != null && status.context() != null) {
                    latestByContext.putIfAbsent(status.context(), status);
                }
            }
            for (Map.Entry<String, CommitStatusDetail> entry : latestByContext.entrySet()) {
                CommitStatusDetail status = entry.getValue();
                String existing = targetStatusStateByContext.get(entry.getKey());
                if (status.state() != null && status.state().equalsIgnoreCase(existing)) {
                    continue;
                }
                try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(
                        request.targetCredentialId(), request.targetInstallationId())) {
                    boolean ok = targetAdapter.replicateCommitStatus(targetFullName, sha, status.state(),
                            status.targetUrl(), status.description(), status.context());
                    if (ok) {
                        statusesReplicated++;
                    } else {
                        errors.add("Status '" + entry.getKey() + "' on " + shortSha + " rejected by destination adapter");
                    }
                } catch (Exception e) {
                    String message = "Status '" + entry.getKey() + "' on " + shortSha + ": " + e.getMessage();
                    if (isAuthFailure(message)) {
                        throw new MetadataSyncException("CI check sync aborted — destination credential rejected: " + message, e);
                    }
                    errors.add(message);
                }
            }
        }

        report(progress, "CI checks · Done: " + checkRunsReplicated + " check run(s) replicated, "
                + checkRunsSkipped + " already in sync, " + statusesReplicated + " status(es) replicated"
                + (errors.isEmpty() ? "" : ", " + errors.size() + " error(s)"));
        return new CiCheckSyncResult(tips.size(), checkRunsFound, checkRunsReplicated, checkRunsSkipped,
                statusesReplicated, nativeCheckRuns, List.copyOf(errors));
    }

    private List<SyncDiffReport.CiCheckRunDetail> listAllCheckRuns(ScmProviderAdapter adapter,
                                                                   String repoFullName,
                                                                   String sha) {
        List<SyncDiffReport.CiCheckRunDetail> all = new ArrayList<>();
        int cursor = 0;
        int pages = 0;
        while (pages < 50) {
            CiCheckPage page = adapter.listCiCheckRunsPage(repoFullName, sha, cursor, 100);
            if (page == null) {
                break;
            }
            if (page.items() != null) {
                all.addAll(page.items());
            }
            pages++;
            if (!page.hasNextPage() || page.nextPage() <= cursor) {
                break;
            }
            cursor = page.nextPage();
        }
        return all;
    }

    /**
     * Most recent head tips in the local bare mirror (newest commit time first, capped at
     * {@code ciCheckTipLimit}). Requires a prior Git sync; empty when no mirror exists yet.
     */
    private List<String> resolveTipShas(Long mappingId) {
        if (mappingId == null) {
            return List.of();
        }
        RepoMapping mapping = repoMappingRepository.findById(mappingId).orElse(null);
        if (mapping == null) {
            return List.of();
        }
        File repoDir = storageTieringService != null
                ? storageTieringService.resolveRepoDirectory(mappingId, mapping.getStorageTier())
                : new File("/tmp/git-utility-mirrors/pair-" + mappingId + ".git");
        if (repoDir == null || !repoDir.exists()) {
            return List.of();
        }
        Map<String, Long> tipTimes = new LinkedHashMap<>();
        try (Git git = Git.open(repoDir)) {
            for (var ref : git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/")) {
                try {
                    var objectId = ref.getObjectId();
                    if (objectId == null) {
                        continue;
                    }
                    try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                        var commit = walk.parseCommit(objectId);
                        tipTimes.put(ref.getName(), commit.getCommitTime() * 1000L);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve CI check tips for mapping #{}: {}", mappingId, e.getMessage());
            return List.of();
        }
        List<String> ordered = tipTimes.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .map(name -> name.substring("refs/heads/".length()))
                .limit(Math.max(1, ciCheckTipLimit))
                .toList();
        List<String> shas = new ArrayList<>();
        if (repoDir.exists()) {
            try (Git git = Git.open(repoDir)) {
                for (String branch : ordered) {
                    var ref = git.getRepository().exactRef("refs/heads/" + branch);
                    if (ref != null && ref.getObjectId() != null) {
                        shas.add(ref.getObjectId().getName());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return shas;
    }

    // ------------------------------------------------------------------
    // Standalone metadata sync jobs (visible in Queue Manager + audit logs)
    // ------------------------------------------------------------------

    /** Creates a visible SyncJob and runs a full release mirror on it. Returns the jobId. */
    public Long launchReleaseSyncJob(Long mappingId) {
        RepoMapping mapping = requireMapping(mappingId);
        SyncJob job = createMetadataJob(mapping, "Standalone release mirror");
        syncTaskExecutor.execute(() -> runReleaseJob(job, mapping));
        return job.getId();
    }

    /** Creates a visible SyncJob and runs a CI check backfill on it. Returns the jobId. */
    public Long launchCiCheckSyncJob(Long mappingId) {
        RepoMapping mapping = requireMapping(mappingId);
        SyncJob job = createMetadataJob(mapping, "Standalone CI check backfill");
        syncTaskExecutor.execute(() -> runCiCheckJob(job, mapping));
        return job.getId();
    }

    private RepoMapping requireMapping(Long mappingId) {
        return repoMappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found for ID: " + mappingId));
    }

    private SyncJob createMetadataJob(RepoMapping mapping, String label) {
        SyncJob job = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(mapping.getRepoAUrl())
                .targetRepo(mapping.getRepoBUrl())
                .ref("*")
                .status(SyncStatus.IN_PROGRESS)
                .triggerType(TriggerType.MANUAL)
                .startedAt(java.time.Instant.now())
                .queueMessageId("metadata-sync")
                .build();
        job = syncJobRepository.save(job);
        audit(job.getId(), LogLevel.INFO, label + " started for pair '" + mapping.getName() + "'");
        webSocketNotificationService.notifyJobUpdated(job);
        return job;
    }

    private void runReleaseJob(SyncJob job, RepoMapping mapping) {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        try {
            pipeline.markCurrent(SyncPipelineState.RELEASES);
            broadcastPipeline(job, pipeline);
            ReleaseSyncResult result = syncReleases(new ReleaseSyncRequest(
                    mapping.getId(), mapping.getRepoAUrl(), mapping.getRepoBUrl(),
                    mapping.getSourceCredentialId(), mapping.getSourceInstallationId(),
                    mapping.getTargetCredentialId(), mapping.getTargetInstallationId(),
                    job.getId(),
                    msg -> audit(job.getId(), LogLevel.INFO, msg)));

            if (result.providerSupported() && result.failed() > 0) {
                pipeline.markFailed(SyncPipelineState.RELEASES,
                        result.mirroredCount() + " mirrored, " + result.failed() + " failed");
            } else {
                pipeline.markDone(SyncPipelineState.RELEASES,
                        result.sourceCount() + " source, " + result.mirroredCount() + " mirrored");
            }
            job.setStatus(SyncStatus.SUCCESS);
            job.setReleasesCount(result.mirroredCount());
            job.setSummaryMessage("Releases: " + result.created() + " created, " + result.updated()
                    + " updated, " + result.unchanged() + " unchanged, " + result.assetsUploaded() + " asset(s)");
            job.setCompletedAt(java.time.Instant.now());
            job.setDurationMs(java.time.Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
            finishJob(job, pipeline, result.errors());
            pairCatchupLedger.recordReleaseSyncCompleted(mapping.getId());
        } catch (Exception e) {
            failJob(job, pipeline, SyncPipelineState.RELEASES, e);
        }
    }

    private void runCiCheckJob(SyncJob job, RepoMapping mapping) {
        SyncPipelineState pipeline = SyncPipelineState.initial();
        try {
            pipeline.markCurrent(SyncPipelineState.CI_CHECKS);
            broadcastPipeline(job, pipeline);
            CiCheckSyncResult result = syncCiChecks(new CiCheckSyncRequest(
                    mapping.getId(), mapping.getRepoAUrl(), mapping.getRepoBUrl(),
                    mapping.getSourceCredentialId(), mapping.getSourceInstallationId(),
                    mapping.getTargetCredentialId(), mapping.getTargetInstallationId(),
                    job.getId(), null,
                    msg -> audit(job.getId(), LogLevel.INFO, msg)));

            if (result.errors().isEmpty()) {
                pipeline.markDone(SyncPipelineState.CI_CHECKS,
                        result.checkRunsReplicated() + " replicated, " + result.statusesReplicated() + " status(es)");
            } else {
                pipeline.markFailed(SyncPipelineState.CI_CHECKS,
                        result.checkRunsReplicated() + " replicated, " + result.errors().size() + " error(s)");
            }
            job.setStatus(result.errors().isEmpty() ? SyncStatus.SUCCESS : SyncStatus.FAILED);
            job.setSummaryMessage("CI checks: " + result.checkRunsReplicated() + " run(s) replicated, "
                    + result.checkRunsSkipped() + " in sync, " + result.statusesReplicated() + " status(es), "
                    + result.tipsInspected() + " tip(s)");
            job.setCompletedAt(java.time.Instant.now());
            job.setDurationMs(java.time.Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
            finishJob(job, pipeline, result.errors());
        } catch (Exception e) {
            failJob(job, pipeline, SyncPipelineState.CI_CHECKS, e);
        }
    }

    private void finishJob(SyncJob job, SyncPipelineState pipeline, List<String> errors) {
        job.setPipelineJson(pipeline.toJson());
        if (errors != null && !errors.isEmpty() && job.getStatus() == SyncStatus.SUCCESS) {
            job.setStatus(SyncStatus.FAILED);
            job.setErrorMessage(String.join("; ", errors.stream().limit(5).toList()));
        }
        job = syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);
        broadcastPipeline(job, pipeline);
        audit(job.getId(), job.getStatus() == SyncStatus.SUCCESS ? LogLevel.INFO : LogLevel.WARN,
                "Metadata sync job finished (" + job.getStatus() + "): " + job.getSummaryMessage());
    }

    private void failJob(SyncJob job, SyncPipelineState pipeline, String stageId, Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.warn("Metadata sync job #{} failed: {}", job.getId(), message);
        pipeline.markFailed(stageId, message.length() > 96 ? message.substring(0, 93) + "..." : message);
        job.setStatus(SyncStatus.FAILED);
        job.setErrorMessage(message);
        job.setCompletedAt(java.time.Instant.now());
        job.setPipelineJson(pipeline.toJson());
        job = syncJobRepository.save(job);
        webSocketNotificationService.notifyJobUpdated(job);
        broadcastPipeline(job, pipeline);
        audit(job.getId(), LogLevel.ERROR, "Metadata sync job failed: " + message);
    }

    private void broadcastPipeline(SyncJob job, SyncPipelineState pipeline) {
        try {
            webSocketNotificationService.notifyPipeline(
                    job.getId(), job.getMappingId(),
                    pipeline.getCurrentStageId(), pipeline.currentLabel(),
                    pipeline.toMap(), null);
        } catch (Exception e) {
            log.debug("Could not broadcast metadata pipeline: {}", e.getMessage());
        }
    }

    private void audit(Long jobId, LogLevel level, String message) {
        if (jobId == null || message == null) {
            return;
        }
        String clipped = message.length() > 1900 ? message.substring(0, 1897) + "..." : message;
        log.info("[job-{}] {}", jobId, clipped);
        try {
            syncAuditLogRepository.save(SyncAuditLog.builder()
                    .jobId(jobId)
                    .level(level != null ? level : LogLevel.INFO)
                    .message(clipped)
                    .build());
        } catch (Exception e) {
            log.debug("Could not persist metadata audit row: {}", e.getMessage());
        }
    }
}
