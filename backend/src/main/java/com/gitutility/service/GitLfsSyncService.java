package com.gitutility.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.entity.RepoMapping;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GitLfsSyncService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final GitHubAuthService gitHubAuthService;
    private final SyncCheckpointService syncCheckpointService;
    private final ProviderRateMeter providerRateMeter;
    private final ExecutorService lfsDiscoveryExecutor;
    private final ExecutorService lfsTransferExecutor;

    @Value("${git-utility.git.lfs-batch-size:50}")
    private int lfsBatchSize;

    @Value("${git-utility.git.lfs-discovery-threads:4}")
    private int lfsDiscoveryThreads;

    public GitLfsSyncService(
            RestTemplate restTemplate,
            GitHubAuthService gitHubAuthService,
            SyncCheckpointService syncCheckpointService,
            ProviderRateMeter providerRateMeter,
            @Qualifier("lfsDiscoveryExecutor") ExecutorService lfsDiscoveryExecutor,
            @Qualifier("lfsTransferExecutor") ExecutorService lfsTransferExecutor) {
        this.restTemplate = restTemplate;
        this.gitHubAuthService = gitHubAuthService;
        this.syncCheckpointService = syncCheckpointService;
        this.providerRateMeter = providerRateMeter;
        this.lfsDiscoveryExecutor = lfsDiscoveryExecutor;
        this.lfsTransferExecutor = lfsTransferExecutor;
    }

    private static final Pattern LFS_POINTER_PATTERN =
            Pattern.compile("version https://git-lfs\\.github\\.com/spec/v1\\s+oid sha256:([a-f0-9]{64})\\s+size (\\d+)", Pattern.MULTILINE);

    public record LfsObject(String oid, long size, String filePath, String headBranch) {
        public LfsObject(String oid, long size) {
            this(oid, size, null, null);
        }
    }

    @FunctionalInterface
    public interface LfsProgressListener {
        void onProgress(String phase, String detail);
    }

    public record LfsInspectResult(
            int totalDiscovered,
            int syncedCount,
            int pendingCount,
            boolean inSync,
            List<LfsObject> objects,
            String mode,
            boolean destVerified,
            List<LfsObject> presentOnDest) {
        public LfsInspectResult(int totalDiscovered, int syncedCount, int pendingCount, boolean inSync,
                                List<LfsObject> objects, String mode) {
            this(totalDiscovered, syncedCount, pendingCount, inSync, objects, mode, true, List.of());
        }

        public LfsInspectResult(int totalDiscovered, int syncedCount, int pendingCount, boolean inSync,
                                List<LfsObject> objects, String mode, boolean destVerified) {
            this(totalDiscovered, syncedCount, pendingCount, inSync, objects, mode, destVerified, List.of());
        }
    }

    /**
     * Full-repo LFS inspection for Refresh Diff: walk every unique branch tip (same discovery as
     * a full mirror), then verify each pointer on the destination via the LFS batch API.
     * Cached checkpoints and trunk-only samples are never used here — page load reads the DB snapshot.
     */
    public LfsInspectResult inspectLfsMirrorForDiff(RepoMapping mapping,
                                                    String sourceRepoUrl,
                                                    String targetRepoUrl,
                                                    Repository repository,
                                                    LfsProgressListener listener) {
        if (repository == null) {
            return new LfsInspectResult(0, 0, 0, true, List.of(), "no-repo", false, List.of());
        }

        if (listener != null) {
            listener.onProgress("discover", "scanning unique objects (rev-list) for LFS pointers");
        }
        Set<String> previousTips = mapping != null
                ? syncCheckpointService.loadLfsScannedTips(mapping)
                : Set.of();
        LfsDiscoveryResult discovery = discoverLfsPointers(repository, previousTips, listener, null, null);
        List<LfsObject> discovered = discovery.objects();
        if (previousTips != null && !previousTips.isEmpty() && mapping != null) {
            if (discovered != null && !discovered.isEmpty()) {
                syncCheckpointService.mergeDiscoveredLfs(mapping.getId(), discovered);
            }
            discovered = syncCheckpointService.loadDiscoveredLfs(mapping);
        } else if (mapping != null && mapping.getId() != null && discovered != null && !discovered.isEmpty()) {
            syncCheckpointService.persistDiscoveredLfs(mapping.getId(), discovered);
        }
        if (discovery.scannedTipOids() != null && !discovery.scannedTipOids().isEmpty() && mapping != null) {
            syncCheckpointService.persistLfsScannedTips(mapping.getId(), discovery.scannedTipOids());
        }
        String mode = previousTips.isEmpty() ? "object-walk" : "object-walk-delta";

        if (discovered == null || discovered.isEmpty()) {
            return new LfsInspectResult(0, 0, 0, true, List.of(), mode + "+none", true, List.of());
        }

        if (listener != null) {
            listener.onProgress("verify",
                    "checking " + discovered.size() + " object(s) on destination via LFS batch API");
        }

        String targetToken = mapping != null ? mapping.getTokenB() : null;
        BatchVerifyResult verify = verifyObjectsPresentOnTarget(
                targetRepoUrl, discovered, listener, targetToken);
        Set<String> presentOnTarget = verify.presentOids();

        List<LfsObject> present = new ArrayList<>();
        int synced = 0;
        for (LfsObject obj : discovered) {
            if (presentOnTarget.contains(obj.oid())) {
                synced++;
                present.add(obj);
            }
        }

        int total = discovered.size();
        int pending = Math.max(0, total - synced);
        if (verify.apiSucceeded()) {
            mode = mode + "+batch-verify";
        } else {
            mode = mode + "+verify-failed";
        }

        return new LfsInspectResult(
                total,
                synced,
                pending,
                total == 0 || (verify.apiSucceeded() && synced >= total),
                discovered,
                mode,
                verify.apiSucceeded(),
                present);
    }

    public List<LfsObject> discoverLfsPointersForBranchTips(Repository repository,
                                                            Collection<String> branchNames,
                                                            LfsProgressListener listener) {
        if (repository == null || branchNames == null || branchNames.isEmpty()) {
            return List.of();
        }
        Set<ObjectId> uniqueCommits = new LinkedHashSet<>();
        for (String branch : branchNames) {
            if (branch == null || branch.isBlank()) {
                continue;
            }
            try {
                Ref ref = repository.exactRef("refs/heads/" + branch);
                if (ref == null) {
                    ref = repository.exactRef("refs/remotes/source/" + branch);
                }
                if (ref != null && ref.getObjectId() != null) {
                    uniqueCommits.add(ref.getObjectId());
                }
            } catch (IOException ignored) {
                // skip unreadable ref
            }
        }
        if (uniqueCommits.isEmpty()) {
            return List.of();
        }

        Set<LfsObject> lfsObjects = new LinkedHashSet<>();
        int totalCommits = uniqueCommits.size();
        int completed = 0;
        for (ObjectId commitId : uniqueCommits) {
            lfsObjects.addAll(scanCommitForLfsPointers(repository, commitId));
            completed++;
            if (listener != null) {
                listener.onProgress("discover", "trunk scan · " + completed + "/" + totalCommits);
            }
        }
        if (listener != null) {
            listener.onProgress("discover", "discovered " + lfsObjects.size() + " pointer(s) on trunk refs");
        }
        return new ArrayList<>(lfsObjects);
    }

    public record BatchVerifyResult(Set<String> presentOids, boolean apiSucceeded) {}

    public Set<String> verifyObjectsPresentOnTarget(String targetRepoUrl,
                                             List<LfsObject> objects,
                                             LfsProgressListener listener) {
        return verifyObjectsPresentOnTarget(targetRepoUrl, objects, listener, null).presentOids();
    }

    public BatchVerifyResult verifyObjectsPresentOnTarget(String targetRepoUrl,
                                                   List<LfsObject> objects,
                                                   LfsProgressListener listener,
                                                   String targetTokenOverride) {
        if (objects == null || objects.isEmpty()) {
            return new BatchVerifyResult(Set.of(), true);
        }
        String targetLfsEndpoint = deriveLfsEndpoint(targetRepoUrl);
        if (targetLfsEndpoint == null) {
            return new BatchVerifyResult(Set.of(), false);
        }
        String targetToken = resolveToken(targetTokenOverride);
        Set<String> present = new LinkedHashSet<>();
        List<List<LfsObject>> batches = partition(objects, Math.max(1, lfsBatchSize));
        int batchIndex = 0;
        boolean anyResponse = false;
        for (List<LfsObject> batch : batches) {
            batchIndex++;
            if (listener != null) {
                listener.onProgress("verify", "destination batch " + batchIndex + "/" + batches.size());
            }
            JsonNode targetBatchResp = callLfsBatchApi(targetLfsEndpoint, "upload", batch, targetToken);
            if (targetBatchResp != null) {
                anyResponse = true;
                present.addAll(extractOidsPresentOnTarget(targetBatchResp));
            }
        }
        return new BatchVerifyResult(present, anyResponse);
    }

    static Set<String> extractOidsPresentOnTarget(JsonNode targetBatchResp) {
        Set<String> present = new LinkedHashSet<>();
        if (targetBatchResp == null) {
            return present;
        }
        JsonNode targetObjects = targetBatchResp.path("objects");
        if (!targetObjects.isArray()) {
            return present;
        }
        for (JsonNode tObj : targetObjects) {
            String oid = tObj.path("oid").asText(null);
            if (oid == null || oid.isBlank()) {
                continue;
            }
            JsonNode uploadAction = tObj.path("actions").path("upload");
            if (uploadAction.isMissingNode() || !uploadAction.has("href")) {
                present.add(oid);
            }
        }
        return present;
    }

    public record LfsDiscoveryResult(List<LfsObject> objects, Set<String> scannedTipOids) {
        public static LfsDiscoveryResult empty() {
            return new LfsDiscoveryResult(List.of(), Set.of());
        }
    }

    public boolean currentTipsMatch(Repository repository, Set<String> previouslyScannedTips) {
        if (repository == null || previouslyScannedTips == null || previouslyScannedTips.isEmpty()) {
            return false;
        }
        try {
            Set<String> current = PairCatchupLedger.tipOidHexes(BareRepoHousekeeping.uniqueBranchTipObjectIds(repository));
            return current.equals(previouslyScannedTips);
        } catch (IOException e) {
            return false;
        }
    }

    public List<LfsObject> discoverLfsPointers(Repository repository) {
        return discoverLfsPointers(repository, null, null, null);
    }

    public List<LfsObject> discoverLfsPointers(Repository repository,
                                               LfsProgressListener listener,
                                               BooleanSupplier cancelCheck,
                                               Long jobId) {
        return discoverLfsPointers(repository, Set.of(), listener, cancelCheck, jobId).objects();
    }

    public LfsDiscoveryResult discoverLfsPointers(Repository repository,
                                                  Set<String> previouslyScannedTips,
                                                  LfsProgressListener listener,
                                                  BooleanSupplier cancelCheck,
                                                  Long jobId) {
        Set<ObjectId> uniqueCommits;
        try {
            uniqueCommits = BareRepoHousekeeping.uniqueBranchTipObjectIds(repository);
        } catch (IOException e) {
            log.warn("Could not list branch tips for LFS discovery: {}", e.getMessage());
            uniqueCommits = Set.of();
        }
        Set<String> scannedHex = PairCatchupLedger.tipOidHexes(uniqueCommits);
        if (uniqueCommits.isEmpty()) {
            return new LfsDiscoveryResult(List.of(), scannedHex);
        }

        Set<ObjectId> uninteresting = new LinkedHashSet<>();
        if (previouslyScannedTips != null) {
            for (String hex : previouslyScannedTips) {
                if (hex == null || hex.isBlank()) {
                    continue;
                }
                try {
                    uninteresting.add(ObjectId.fromString(hex.trim()));
                } catch (Exception ignored) {
                    // skip malformed
                }
            }
        }

        if (listener != null) {
            if (uninteresting.isEmpty()) {
                listener.onProgress("discover",
                        "scanning unique objects (rev-list) from " + uniqueCommits.size() + " tip(s)");
            } else {
                listener.onProgress("discover",
                        "scanning new objects (rev-list --not " + uninteresting.size() + " prior tip(s))");
            }
        }

        Set<LfsObject> found = scanReachableBlobsForLfsPointers(
                repository, uniqueCommits, uninteresting, listener, cancelCheck, jobId);
        if (listener != null) {
            listener.onProgress("discover", "discovered " + found.size() + " pointer(s)");
        }
        return new LfsDiscoveryResult(new ArrayList<>(found), scannedHex);
    }

    private Set<LfsObject> scanReachableBlobsForLfsPointers(Repository repository,
                                                            Set<ObjectId> startTips,
                                                            Set<ObjectId> uninterestingTips,
                                                            LfsProgressListener listener,
                                                            BooleanSupplier cancelCheck,
                                                            Long jobId) {
        Set<LfsObject> found = new LinkedHashSet<>();
        if (repository == null || startTips == null || startTips.isEmpty()) {
            return found;
        }
        AtomicInteger blobs = new AtomicInteger(0);
        AtomicLong lastEmitMs = new AtomicLong(0);
        try (ObjectWalk walk = new ObjectWalk(repository)) {
            for (ObjectId tip : startTips) {
                throwIfCancelled(cancelCheck, jobId);
                try {
                    walk.markStart(walk.parseAny(tip));
                } catch (Exception e) {
                    log.debug("LFS ObjectWalk skip start {}: {}", tip.name(), e.getMessage());
                }
            }
            if (uninterestingTips != null) {
                for (ObjectId old : uninterestingTips) {
                    try {
                        walk.markUninteresting(walk.parseAny(old));
                    } catch (Exception ignored) {
                        // tip may have been gc'd
                    }
                }
            }
            while (walk.next() != null) {
                throwIfCancelled(cancelCheck, jobId);
            }
            RevObject obj;
            while ((obj = walk.nextObject()) != null) {
                throwIfCancelled(cancelCheck, jobId);
                if (obj.getType() != Constants.OBJ_BLOB) {
                    continue;
                }
                int seen = blobs.incrementAndGet();
                long now = System.currentTimeMillis();
                if (listener != null && (seen == 1 || now - lastEmitMs.get() >= 2000)) {
                    lastEmitMs.set(now);
                    listener.onProgress("discover", "scanning unique objects · " + seen + " blob(s)");
                }
                LfsObject pointer = pointerFromBlob(repository, obj);
                if (pointer != null) {
                    found.add(pointer);
                }
            }
        } catch (JobCancelledException e) {
            throw e;
        } catch (JobPausedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LFS ObjectWalk discovery notice: {}", e.getMessage());
        }
        return found;
    }

    static LfsObject pointerFromBlob(Repository repository, RevObject blob) {
        if (repository == null || blob == null) {
            return null;
        }
        try {
            ObjectLoader loader = repository.open(blob);
            long size = loader.getSize();
            if (size < 42 || size >= 500) {
                return null;
            }
            byte[] bytes = loader.getBytes();
            if (!looksLikeLfsPointer(bytes)) {
                return null;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            Matcher matcher = LFS_POINTER_PATTERN.matcher(content);
            if (matcher.find()) {
                return new LfsObject(matcher.group(1), Long.parseLong(matcher.group(2)));
            }
        } catch (Exception e) {
            log.debug("LFS blob inspect {}: {}", blob.name(), e.getMessage());
        }
        return null;
    }

    static boolean looksLikeLfsPointer(byte[] bytes) {
        if (bytes == null || bytes.length < 42) {
            return false;
        }
        String prefix = "version https://git-lfs.github.com/spec/v1";
        byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    static Set<LfsObject> scanCommitForLfsPointers(Repository repository, ObjectId commitId) {
        Set<LfsObject> found = new HashSet<>();
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            RevTree tree = commit.getTree();
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    if (loader.getSize() < 500) {
                        byte[] bytes = loader.getBytes();
                        String content = new String(bytes, StandardCharsets.UTF_8);
                        Matcher matcher = LFS_POINTER_PATTERN.matcher(content);
                        if (matcher.find()) {
                            String oid = matcher.group(1);
                            long size = Long.parseLong(matcher.group(2));
                            found.add(new LfsObject(oid, size, treeWalk.getPathString(), null));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error inspecting commit {} for LFS pointers: {}", commitId.name(), e.getMessage());
        }
        return found;
    }

    public record LfsSyncStats(int count, long bytes, int failed, int alreadyPresent) {
        public LfsSyncStats(int count, long bytes, int failed) {
            this(count, bytes, failed, 0);
        }

        public static LfsSyncStats empty() {
            return new LfsSyncStats(0, 0, 0, 0);
        }
    }

    private record TransferTask(String oid, Callable<Boolean> task) {}

    public LfsSyncStats syncLfsObjects(String sourceRepoUrl, String targetRepoUrl, List<LfsObject> objects) {
        return syncLfsObjects(sourceRepoUrl, targetRepoUrl, objects, null, null, null, null, null);
    }

    public LfsSyncStats syncLfsObjects(String sourceRepoUrl,
                                       String targetRepoUrl,
                                       List<LfsObject> objects,
                                       LfsProgressListener listener,
                                       BooleanSupplier cancelCheck,
                                       Long jobId) {
        return syncLfsObjects(sourceRepoUrl, targetRepoUrl, objects, listener, cancelCheck, jobId, null, null);
    }

    /**
     * Synchronizes Git LFS binary blobs from source SCM to target SCM in batches.
     */
    public LfsSyncStats syncLfsObjects(String sourceRepoUrl,
                                       String targetRepoUrl,
                                       List<LfsObject> objects,
                                       LfsProgressListener listener,
                                       BooleanSupplier cancelCheck,
                                       Long jobId,
                                       String sourceTokenOverride,
                                       String targetTokenOverride) {
        return syncLfsObjects(sourceRepoUrl, targetRepoUrl, objects, listener, cancelCheck, jobId,
                sourceTokenOverride, targetTokenOverride, null);
    }

    public LfsSyncStats syncLfsObjects(String sourceRepoUrl,
                                       String targetRepoUrl,
                                       List<LfsObject> objects,
                                       LfsProgressListener listener,
                                       BooleanSupplier cancelCheck,
                                       Long jobId,
                                       String sourceTokenOverride,
                                       String targetTokenOverride,
                                       Consumer<String> onTransferSuccess) {
        if (objects == null || objects.isEmpty()) {
            return LfsSyncStats.empty();
        }

        log.info("Synchronizing {} Git LFS objects from {} to {}", objects.size(), sourceRepoUrl, targetRepoUrl);
        String sourceLfsEndpoint = deriveLfsEndpoint(sourceRepoUrl);
        String targetLfsEndpoint = deriveLfsEndpoint(targetRepoUrl);

        if (sourceLfsEndpoint == null || targetLfsEndpoint == null) {
            log.debug("Could not derive LFS batch endpoints for mirroring.");
            return LfsSyncStats.empty();
        }

        String sourceToken = resolveToken(sourceTokenOverride);
        String targetToken = resolveToken(targetTokenOverride);

        Map<String, Long> sizesByOid = new HashMap<>();
        for (LfsObject obj : objects) {
            sizesByOid.put(obj.oid(), obj.size());
        }

        int transferredCount = 0;
        int alreadyPresentCount = 0;
        int failedCount = 0;
        long transferredBytes = 0;
        int batchSize = Math.max(1, lfsBatchSize);
        List<List<LfsObject>> batches = partition(objects, batchSize);
        int batchIndex = 0;
        try {
            for (List<LfsObject> batch : batches) {
                throwIfCancelled(cancelCheck, jobId);
                batchIndex++;
                if (listener != null) {
                    listener.onProgress("transfer",
                            "batch " + batchIndex + "/" + batches.size()
                                    + " · " + transferredCount + "/" + objects.size() + " object(s)");
                }

                JsonNode sourceBatchResp = callLfsBatchApi(sourceLfsEndpoint, "download", batch, sourceToken);
                JsonNode targetBatchResp = callLfsBatchApi(targetLfsEndpoint, "upload", batch, targetToken);

                if (sourceBatchResp == null || targetBatchResp == null) {
                    failedCount += batch.size();
                    continue;
                }

                JsonNode sourceObjects = sourceBatchResp.path("objects");
                JsonNode targetObjects = targetBatchResp.path("objects");

                Map<String, JsonNode> sourceMap = new HashMap<>();
                if (sourceObjects.isArray()) {
                    for (JsonNode o : sourceObjects) {
                        sourceMap.put(o.path("oid").asText(), o);
                    }
                }

                List<TransferTask> transfers = new ArrayList<>();
                if (targetObjects.isArray()) {
                    for (JsonNode tObj : targetObjects) {
                        throwIfCancelled(cancelCheck, jobId);
                        String oid = tObj.path("oid").asText();
                        JsonNode uploadAction = tObj.path("actions").path("upload");

                        if (!uploadAction.isMissingNode() && uploadAction.has("href")) {
                            JsonNode sObj = sourceMap.get(oid);
                            JsonNode downloadAction = sObj != null ? sObj.path("actions").path("download") : null;
                            if (downloadAction != null && downloadAction.has("href")) {
                                String downloadUrl = downloadAction.path("href").asText();
                                String uploadUrl = uploadAction.path("href").asText();
                                transfers.add(new TransferTask(oid,
                                        () -> {
                                            if (providerRateMeter != null && jobId != null) {
                                                providerRateMeter.attachJob(jobId);
                                            }
                                            try {
                                                return transferLfsBlob(downloadUrl, downloadAction, uploadUrl, uploadAction);
                                            } finally {
                                                if (providerRateMeter != null) {
                                                    providerRateMeter.detachJob();
                                                }
                                            }
                                        }));
                            } else {
                                failedCount++;
                            }
                        } else if (oid != null && !oid.isBlank()) {
                            alreadyPresentCount++;
                            if (onTransferSuccess != null) {
                                onTransferSuccess.accept(oid);
                            }
                        }
                    }
                }

                if (!transfers.isEmpty()) {
                    List<Callable<Boolean>> callables = transfers.stream().map(TransferTask::task).toList();
                    List<Future<Boolean>> results = lfsTransferExecutor.invokeAll(callables);
                    for (int i = 0; i < results.size(); i++) {
                        String oid = transfers.get(i).oid();
                        try {
                            if (Boolean.TRUE.equals(results.get(i).get())) {
                                transferredCount++;
                                transferredBytes += sizesByOid.getOrDefault(oid, 0L);
                                if (onTransferSuccess != null) {
                                    onTransferSuccess.accept(oid);
                                }
                            } else {
                                failedCount++;
                            }
                        } catch (ExecutionException e) {
                            failedCount++;
                        }
                    }
                }
            }

            if (failedCount > 0) {
                log.warn("LFS transfer completed with {} failure(s) out of {} object(s)", failedCount, objects.size());
            } else {
                log.info("Successfully transferred {} LFS binary blobs to destination", transferredCount);
            }
        } catch (JobCancelledException e) {
            throw e;
        } catch (JobPausedException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LFS binary transfer interrupted");
            failedCount = Math.max(failedCount, objects.size() - transferredCount - alreadyPresentCount);
        } catch (Exception e) {
            log.warn("LFS binary transfer encountered an issue: {}", e.getMessage());
            failedCount = Math.max(failedCount, objects.size() - transferredCount - alreadyPresentCount);
        }

        if (listener != null) {
            listener.onProgress("transfer",
                    (transferredCount + alreadyPresentCount) + "/" + objects.size() + " object(s) on destination"
                            + (alreadyPresentCount > 0 ? " · " + alreadyPresentCount + " already present" : "")
                            + (failedCount > 0 ? " · " + failedCount + " failed" : ""));
        }
        return new LfsSyncStats(transferredCount + alreadyPresentCount, transferredBytes, failedCount, alreadyPresentCount);
    }

    private String resolveToken(String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return gitHubAuthService.getEffectiveGitHubToken();
    }

    static List<org.eclipse.jgit.lib.Ref> collectBranchScanRefs(Repository repository) {
        try {
            Map<String, org.eclipse.jgit.lib.Ref> byBranch = new LinkedHashMap<>();
            for (String branch : BareRepoHousekeeping.listHeadBranchNames(repository)) {
                org.eclipse.jgit.lib.Ref ref = repository.exactRef("refs/heads/" + branch);
                if (ref != null) {
                    byBranch.putIfAbsent(branch, ref);
                }
            }
            for (org.eclipse.jgit.lib.Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/remotes/source/")) {
                byBranch.putIfAbsent(branchLabel(ref.getName()), ref);
            }
            return new ArrayList<>(byBranch.values());
        } catch (Exception e) {
            return List.of();
        }
    }

    static String branchLabel(String refName) {
        if (refName == null) {
            return "";
        }
        if (refName.startsWith("refs/remotes/source/")) {
            return refName.substring("refs/remotes/source/".length());
        }
        if (refName.startsWith("refs/heads/")) {
            return refName.substring("refs/heads/".length());
        }
        return refName;
    }

    static int discoveryChunkSize(int configuredThreads, int totalCommits) {
        return Math.max(1, Math.min(configuredThreads, totalCommits));
    }

    static <T> List<List<T>> partition(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return batches;
        }
        int size = Math.max(1, batchSize);
        for (int i = 0; i < items.size(); i += size) {
            batches.add(new ArrayList<>(items.subList(i, Math.min(i + size, items.size()))));
        }
        return batches;
    }

    private static void throwIfCancelled(BooleanSupplier cancelCheck, Long jobId) {
        if (cancelCheck != null && cancelCheck.getAsBoolean()) {
            throw new JobCancelledException(jobId);
        }
    }

    private JsonNode callLfsBatchApi(String lfsEndpoint, String operation, List<LfsObject> objects, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/vnd.git-lfs+json");
            headers.set("Content-Type", "application/vnd.git-lfs+json");
            if (token != null && !token.isBlank()) {
                headers.setBearerAuth(token.trim());
            }

            Map<String, Object> body = new HashMap<>();
            body.put("operation", operation);
            body.put("transfers", List.of("basic"));

            List<Map<String, Object>> objsPayload = new ArrayList<>();
            for (LfsObject obj : objects) {
                objsPayload.add(Map.of("oid", obj.oid(), "size", obj.size()));
            }
            body.put("objects", objsPayload);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String batchUrl = lfsEndpoint + "/objects/batch";

            ResponseEntity<String> response = restTemplate.exchange(URI.create(batchUrl), HttpMethod.POST, entity, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.debug("LFS Batch API call ({}) failed: {}", operation, e.getMessage());
            return null;
        }
    }

    static void applyActionHeaders(HttpHeaders target, JsonNode action) {
        if (target == null || action == null || action.isMissingNode()) {
            return;
        }
        JsonNode headerNode = action.path("header");
        if (!headerNode.isObject()) {
            return;
        }
        headerNode.forEachEntry((key, value) -> {
            if (value != null && !value.isNull()) {
                target.set(key, value.asString());
            }
        });
    }

    private boolean transferLfsBlob(String downloadUrl, JsonNode downloadAction,
                                      String uploadUrl, JsonNode uploadAction) {
        try {
            restTemplate.execute(URI.create(downloadUrl), HttpMethod.GET, request -> {
                applyActionHeaders(request.getHeaders(), downloadAction);
            }, response -> {
                try (InputStream in = response.getBody()) {
                    restTemplate.execute(URI.create(uploadUrl), HttpMethod.PUT, putRequest -> {
                        applyActionHeaders(putRequest.getHeaders(), uploadAction);
                        if (!putRequest.getHeaders().containsHeader(HttpHeaders.CONTENT_TYPE)) {
                            putRequest.getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
                        }
                        if (in != null) {
                            in.transferTo(putRequest.getBody());
                        }
                    }, putResponse -> {
                        if (!putResponse.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("LFS upload HTTP " + putResponse.getStatusCode().value());
                        }
                        return null;
                    });
                }
                return null;
            });
            return true;
        } catch (Exception e) {
            log.warn("Failed to stream LFS blob: {}", e.getMessage());
            return false;
        }
    }

    private String deriveLfsEndpoint(String repoUrl) {
        if (repoUrl == null) return null;
        String clean = repoUrl.trim().replaceAll("\\.git$", "");
        if (clean.contains("github.com")) {
            return clean + ".git/info/lfs";
        }
        return clean + "/info/lfs";
    }
}
