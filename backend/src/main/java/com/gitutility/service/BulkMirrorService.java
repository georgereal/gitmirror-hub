package com.gitutility.service;

import com.gitutility.model.dto.BulkMirrorRequest;
import com.gitutility.model.dto.BulkMirrorResponse;
import com.gitutility.model.dto.CreateRepoRequest;
import com.gitutility.model.dto.GitHubRepoOption;
import com.gitutility.model.dto.TestConnectionRequest;
import com.gitutility.model.entity.BulkSubmission;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Bulk repo migration submission (Add Repo Pair modal → Bulk migration tab).
 *
 * <p>Validates every row, skips rows that would collide (warn + skip, never silent mapping),
 * probes destination existence (Option 1) / write access + has-commits (Option 2) in bounded
 * chunks, then creates one pair + one bootstrap full-mirror job per accepted row. Skipped rows
 * never become pairs/jobs; their outcomes persist on the {@code bulk_submissions} record.
 *
 * <p>Concurrency is NOT capped here: accepted jobs flow into the existing full/incremental
 * lanes and run on whatever worker threads/consumer capacity is available (rabbit listener
 * pool or {@code NoneSyncEventBus} workers); extras wait as {@code QUEUED}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkMirrorService {

    public static final String MODE_CREATE_DEST = "CREATE_DEST";
    public static final String MODE_USE_EXISTING = "USE_EXISTING";
    /** Per row: blank destUrl creates at job start; destUrl maps an existing repository. */
    public static final String MODE_SELECTIVE = "SELECTIVE";

    private final RepoMappingRepository mappingRepository;
    private final BulkSubmissionRepository bulkSubmissionRepository;
    private final RepoMappingService repoMappingService;
    private final ScmProviderFacade scmProviderFacade;
    private final ScmCredentialService scmCredentialService;
    private final FeatureFlagsService featureFlagsService;
    private final ObjectMapper objectMapper;

    /** Soft guard on the synchronous request (probes), NOT on concurrent execution. */
    @Value("${git-utility.bulk.max-items:1000}")
    private int maxItems;

    @Value("${git-utility.bulk.probe-chunk-size:250}")
    private int probeChunkSize;

    @Value("${git-utility.bulk.probe-concurrency:5}")
    private int probeConcurrency;

    public BulkMirrorResponse submit(BulkMirrorRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Bulk submission requires at least one repository item.");
        }
        String mode = request.getMode() == null ? "" : request.getMode().trim().toUpperCase();
        if (!MODE_CREATE_DEST.equals(mode) && !MODE_USE_EXISTING.equals(mode) && !MODE_SELECTIVE.equals(mode)) {
            throw new IllegalArgumentException("mode must be CREATE_DEST, USE_EXISTING, or SELECTIVE");
        }
        if (request.getItems().size() > maxItems) {
            throw new IllegalArgumentException(
                    "Bulk submission exceeds the soft request guard of " + maxItems
                            + " items (git-utility.bulk.max-items). Execution itself is uncapped — submit in waves.");
        }
        boolean anyCreate = MODE_CREATE_DEST.equals(mode)
                || (MODE_SELECTIVE.equals(mode) && request.getItems().stream().anyMatch(BulkMirrorService::isCreateRow));
        if (anyCreate && (request.getDestCredentialId() == null || request.getDestCredentialId().isBlank())) {
            throw new IllegalArgumentException("Creating destinations requires a destination credential.");
        }

        BulkSubmission submission = bulkSubmissionRepository.save(BulkSubmission.builder()
                .mode(mode)
                .itemCount(request.getItems().size())
                .createdCount(0)
                .skippedJson("[]")
                .build());

        List<BulkMirrorResponse.Row> rows = process(request, mode, submission.getId());
        BulkMirrorResponse response = BulkMirrorResponse.of(submission.getId(), rows);

        BulkSubmission sub = bulkSubmissionRepository.findById(submission.getId()).orElse(null);
        if (sub != null) {
            sub.setCreatedCount(response.getCreatedQueuedCount());
            sub.setSkippedJson(writeSkippedJson(rows));
            bulkSubmissionRepository.save(sub);
        }
        log.info("Bulk submission #{} [{}]: {} queued, {} skipped, {} invalid",
                submission.getId(), mode, response.getCreatedQueuedCount(),
                response.getSkippedCount(), response.getFailedValidationCount());
        return response;
    }

    private List<BulkMirrorResponse.Row> process(BulkMirrorRequest request, String mode, String submissionId) {
        List<RepoMapping> existingMappings = mappingRepository.findAll();
        Set<String> seenSources = new HashSet<>();
        Set<String> seenDestinations = new HashSet<>();
        Map<Integer, BulkMirrorResponse.Row> rows = new LinkedHashMap<>();
        Map<Integer, String> destUrls = new LinkedHashMap<>();
        Set<Integer> createRows = new HashSet<>();
        List<Integer> candidates = new ArrayList<>();

        // Phase A/B: per-row basic validation, dedupe, destination URL + collision checks
        for (int i = 0; i < request.getItems().size(); i++) {
            BulkMirrorRequest.BulkItem item = request.getItems().get(i);
            String sourceUrl = item.getSourceUrl() == null ? "" : item.getSourceUrl().trim();
            String sourceKey = RepoMappingService.normalizeRepoKey(sourceUrl);

            if (sourceUrl.isBlank()) {
                rows.put(i, skip("Missing source repository URL"));
                continue;
            }
            if (!seenSources.add(sourceKey)) {
                rows.put(i, skip("Duplicate source in this submission — already selected"));
                continue;
            }

            boolean createRow = MODE_CREATE_DEST.equals(mode) || (MODE_SELECTIVE.equals(mode) && isCreateRow(item));
            String destUrl;
            try {
                destUrl = createRow
                        ? buildCreateDestUrl(request, item, sourceUrl)
                        : item.getDestUrl() == null ? "" : item.getDestUrl().trim();
            } catch (IllegalArgumentException e) {
                rows.put(i, fail(e.getMessage()));
                continue;
            }
            String destKey = RepoMappingService.normalizeRepoKey(destUrl);
            if (destUrl.isBlank() || destKey.isEmpty()) {
                rows.put(i, createRow
                        ? fail("Destination owner/name could not be resolved — check the destination credential and owner")
                        : skip("Missing destination repository URL"));
                continue;
            }
            if (destKey.equals(sourceKey)) {
                rows.put(i, skip("Source and destination are the same repository"));
                continue;
            }
            if (!seenDestinations.add(destKey)) {
                rows.put(i, skip("Duplicate destination in this submission — another source already targets it"));
                continue;
            }
            destUrls.put(i, destUrl);
            if (createRow) {
                createRows.add(i);
            }

            // Existing-pair collisions (user decision: warn + skip, never silently map)
            String collision = findCollision(existingMappings, sourceKey, destKey);
            if (collision != null) {
                rows.put(i, skip(collision));
                continue;
            }
            candidates.add(i);
        }

        // Phase C: destination probes in bounded chunks (the only rate-limit guard at submission)
        List<Integer> createCandidates = candidates.stream().filter(createRows::contains).toList();
        List<Integer> existingCandidates = candidates.stream().filter(i -> !createRows.contains(i)).toList();
        if (!createCandidates.isEmpty()) {
            probeCreateDest(request, createCandidates, rows, destUrls);
        }
        if (!existingCandidates.isEmpty()) {
            probeUseExisting(request, existingCandidates, rows, destUrls);
        }

        // Phase D: create pairs + enqueue bootstrap full-mirror jobs
        List<BulkMirrorResponse.Row> ordered = new ArrayList<>();
        for (int i = 0; i < request.getItems().size(); i++) {
            BulkMirrorResponse.Row row = rows.get(i);
            if (row != null) {
                ordered.add(row);
                continue;
            }
            if (!candidates.contains(i)) {
                continue;
            }
            BulkMirrorRequest.BulkItem item = request.getItems().get(i);
            ordered.add(createPair(request, mode, submissionId, item, destUrls.get(i), createRows.contains(i)));
        }
        return ordered;
    }

    /** Option 1: destination must not already exist remotely — else warn + skip (review decision). */
    private void probeCreateDest(BulkMirrorRequest request, List<Integer> candidates,
                                 Map<Integer, BulkMirrorResponse.Row> rows, Map<Integer, String> destUrls) {
        Map<Integer, Boolean> exists = runProbes(candidates,
                i -> scmProviderFacade.repositoryExists(destUrls.get(i), request.getDestCredentialId()));
        for (Integer i : candidates) {
            if (Boolean.TRUE.equals(exists.get(i))) {
                rows.put(i, skip("Destination already exists — a mirror is already present; skipped"));
            }
        }
    }

    /** Option 2: WRITE verification (hard fail) + has-commits probe (operator decides per row). */
    private void probeUseExisting(BulkMirrorRequest request, List<Integer> candidates,
                                  Map<Integer, BulkMirrorResponse.Row> rows, Map<Integer, String> destUrls) {
        Map<Integer, Boolean> hasCommits = runProbes(candidates,
                i -> scmProviderFacade.hasCommits(destUrls.get(i), request.getItems().get(i).getDestCredentialId()));
        Map<Integer, Boolean> writeOk = runProbes(candidates, i -> {
            TestConnectionRequest req = TestConnectionRequest.builder()
                    .repoUrl(destUrls.get(i))
                    .requiredAccess("WRITE")
                    .knownPrivate(true)
                    .credentialId(request.getItems().get(i).getDestCredentialId())
                    .build();
            try {
                return scmProviderFacade.testConnection(null, req).isValid();
            } catch (Exception e) {
                log.debug("Bulk WRITE probe failed for {}: {}", destUrls.get(i), e.getMessage());
                return false;
            }
        });
        for (Integer i : candidates) {
            if (!Boolean.TRUE.equals(writeOk.get(i))) {
                rows.put(i, fail("Destination credential cannot write to this repository — fix credentials and resubmit"));
            }
        }
        for (Integer i : candidates) {
            if (rows.containsKey(i)) {
                continue;
            }
            boolean nonEmpty = Boolean.TRUE.equals(hasCommits.get(i));
            boolean included = Boolean.TRUE.equals(request.getItems().get(i).getIncludeNonEmptyDest());
            if (nonEmpty && !included) {
                rows.put(i, skip("Destination has existing content — excluded by operator"));
            }
        }
    }

    /** Runs a probe per candidate in chunks of {@code probeChunkSize}, bounded concurrency inside each chunk. */
    private Map<Integer, Boolean> runProbes(List<Integer> candidates,
                                            java.util.function.Function<Integer, Boolean> probeFn) {
        Map<Integer, Boolean> out = new LinkedHashMap<>();
        if (candidates.isEmpty()) {
            return out;
        }
        int chunk = Math.max(1, probeChunkSize);
        int parallelism = Math.max(1, Math.min(probeConcurrency, chunk));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "bulk-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            for (int start = 0; start < candidates.size(); start += chunk) {
                List<Integer> chunkIndices = candidates.subList(start, Math.min(start + chunk, candidates.size()));
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (Integer i : chunkIndices) {
                    tasks.add(() -> {
                        try {
                            return Boolean.TRUE.equals(probeFn.apply(i));
                        } catch (Exception e) {
                            log.debug("Bulk probe failed for item {}: {}", i, e.getMessage());
                            return false;
                        }
                    });
                }
                try {
                    List<Future<Boolean>> futures = pool.invokeAll(tasks);
                    for (int j = 0; j < futures.size(); j++) {
                        Integer index = chunkIndices.get(j);
                        try {
                            out.put(index, Boolean.TRUE.equals(futures.get(j).get()));
                        } catch (Exception e) {
                            out.put(index, false);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    for (Integer index : chunkIndices) {
                        out.put(index, false);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    private static boolean isCreateRow(BulkMirrorRequest.BulkItem item) {
        return item == null || item.getDestUrl() == null || item.getDestUrl().isBlank();
    }

    private static RepoVisibility resolvedDestVisibility(BulkMirrorRequest request) {
        if (request.getDestVisibility() != null) {
            return request.getDestVisibility();
        }
        if (request.getDestPrivate() != null && !request.getDestPrivate()) {
            return RepoVisibility.PUBLIC;
        }
        return RepoVisibility.PRIVATE;
    }

    /** Creates the mapping and enqueues the bootstrap full-mirror job for one accepted row. */
    private BulkMirrorResponse.Row createPair(BulkMirrorRequest request, String mode, String submissionId,
                                              BulkMirrorRequest.BulkItem item, String destUrl, boolean createDest) {
        String sourceUrl = item.getSourceUrl().trim();
        try {
            boolean publicOnly = item.getSourceVisibility() == RepoVisibility.PUBLIC
                    && (item.getSourceCredentialId() == null || item.getSourceCredentialId().isBlank());
            RepoMapping mapping = RepoMapping.builder()
                    .name(ensureUniqueName(deriveRepoName(sourceUrl, createDest ? item.getDestName() : null)))
                    .repoAUrl(sourceUrl)
                    .repoBUrl(destUrl)
                    .branchPattern(request.getBranchPattern())
                    .syncDirection(request.getSyncDirection())
                    .trunkConflictPolicy(request.getTrunkConflictPolicy())
                    .storageTier(request.getStorageTier())
                    .active(request.getActive() == null || request.getActive())
                    .sourceProvider(item.getSourceProvider())
                    .sourceCredentialId(item.getSourceCredentialId())
                    .sourceInstallationId(item.getSourceInstallationId())
                    .sourceVisibility(item.getSourceVisibility())
                    .sourcePublicRead(publicOnly ? Boolean.TRUE : Boolean.FALSE)
                    .targetProvider(resolveProvider(destUrl))
                    .targetCredentialId(createDest ? request.getDestCredentialId() : item.getDestCredentialId())
                    .targetInstallationId(createDest ? request.getDestInstallationId() : item.getDestInstallationId())
                    .targetVisibility(createDest
                            ? resolvedDestVisibility(request)
                            : (item.getDestVisibility() != null ? item.getDestVisibility() : RepoVisibility.UNKNOWN))
                    .destinationAutoCreate(createDest)
                    .bulkSubmissionId(submissionId)
                    .build();

            // Same guards as the single-pair path — row-level failures never abort the batch.
            featureFlagsService.assertMappingAllowed(mapping);
            repoMappingService.validateNoRepositoryCollisions(null, mapping);
            scmCredentialService.requireBoundIfGithub(mapping);

            RepoMapping saved = mappingRepository.save(mapping);
            String jobId = null;
            if (saved.isActive()) {
                try {
                    jobId = repoMappingService.triggerInitialBootstrapSync(saved).getId();
                } catch (Exception e) {
                    log.error("Bulk: failed to enqueue bootstrap sync for '{}' (submission {}): {}",
                            saved.getName(), submissionId, e.getMessage());
                }
            }
            return BulkMirrorResponse.Row.builder()
                    .sourceUrl(sourceUrl)
                    .destUrl(destUrl)
                    .outcome(BulkMirrorResponse.Outcome.CREATED_QUEUED)
                    .mappingId(saved.getId())
                    .jobId(jobId)
                    .build();
        } catch (Exception e) {
            log.warn("Bulk row failed for source {}: {}", sourceUrl, e.getMessage());
            return fail(e.getMessage() == null ? "Row failed validation" : e.getMessage());
        }
    }

    /**
     * Option 1 destination URL: {@code {host}/{owner}/{name}.git}. Owner defaults to the
     * destination credential's account login; host to the credential's GHES host (or github.com).
     */
    private String buildCreateDestUrl(BulkMirrorRequest request, BulkMirrorRequest.BulkItem item, String sourceUrl) {
        String owner = request.getDestOwner();
        if (owner == null || owner.isBlank()) {
            owner = scmCredentialService.require(request.getDestCredentialId()).getAccountLogin();
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Destination owner is required — pick the owner/org for created repos");
        }
        String name = item.getDestName() != null && !item.getDestName().isBlank()
                ? item.getDestName().trim()
                : deriveRepoName(sourceUrl, null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Destination name is required");
        }
        return credentialHost(request.getDestCredentialId()) + "/" + owner.trim() + "/" + name + ".git";
    }

    private String credentialHost(String credentialId) {
        if (credentialId == null) {
            return "https://github.com";
        }
        try {
            String hostUrl = scmCredentialService.require(credentialId).getHostUrl();
            if (hostUrl != null && !hostUrl.isBlank()) {
                String trimmed = hostUrl.trim().replaceAll("/+$", "");
                return trimmed.startsWith("http") ? trimmed : "https://" + trimmed;
            }
        } catch (Exception ignored) {
            // Fall through to github.com default
        }
        return "https://github.com";
    }

    private String resolveProvider(String repoUrl) {
        try {
            return scmProviderFacade.getAdapterForUrl(repoUrl).getProviderType().name();
        } catch (Exception e) {
            return null;
        }
    }

    /** Source/destination vs existing-pair collision message (review decision: warn + skip). */
    private String findCollision(List<RepoMapping> existingMappings, String sourceKey, String destKey) {
        for (RepoMapping existing : existingMappings) {
            if (!existing.isActive()) {
                continue;
            }
            String existA = RepoMappingService.normalizeRepoKey(existing.getRepoAUrl());
            String existB = RepoMappingService.normalizeRepoKey(existing.getRepoBUrl());
            if (sourceKey.equals(existA) || sourceKey.equals(existB)) {
                return "Source is already participating in active mirror pair '" + existing.getName()
                        + "' — mirror already present; skipped";
            }
            if (destKey.equals(existA) || destKey.equals(existB)) {
                return "Destination is already participating in active mirror pair '" + existing.getName()
                        + "' — mirror already present; skipped";
            }
        }
        return null;
    }

    private String ensureUniqueName(String base) {
        String clean = (base == null || base.isBlank()) ? "bulk-mirror" : base.trim();
        if (mappingRepository.findByName(clean).isEmpty()) {
            return clean;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = clean + "-" + suffix;
            if (mappingRepository.findByName(candidate).isEmpty()) {
                return candidate;
            }
        }
        return clean + "-" + System.currentTimeMillis();
    }

    private String deriveRepoName(String sourceUrl, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName.trim();
        }
        try {
            String cleaned = sourceUrl.replaceAll("\\.git$", "").replaceAll("/+$", "");
            int slash = cleaned.lastIndexOf('/');
            if (slash != -1 && slash < cleaned.length() - 1) {
                return cleaned.substring(slash + 1);
            }
        } catch (Exception ignored) {
            // Fall through
        }
        return "bulk-mirror";
    }

    private BulkMirrorResponse.Row skip(String reason) {
        return BulkMirrorResponse.Row.builder()
                .outcome(BulkMirrorResponse.Outcome.SKIPPED)
                .reason(reason)
                .build();
    }

    private BulkMirrorResponse.Row fail(String reason) {
        return BulkMirrorResponse.Row.builder()
                .outcome(BulkMirrorResponse.Outcome.FAILED_VALIDATION)
                .reason(reason)
                .build();
    }

    private String writeSkippedJson(List<BulkMirrorResponse.Row> rows) {
        try {
            List<Map<String, Object>> skipped = new ArrayList<>();
            for (BulkMirrorResponse.Row row : rows) {
                if (row.getOutcome() != BulkMirrorResponse.Outcome.CREATED_QUEUED) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("outcome", row.getOutcome() == null ? null : row.getOutcome().name());
                    entry.put("sourceUrl", row.getSourceUrl());
                    entry.put("destUrl", row.getDestUrl());
                    entry.put("reason", row.getReason());
                    skipped.add(entry);
                }
            }
            return objectMapper.writeValueAsString(skipped);
        } catch (Exception e) {
            log.debug("Bulk skipped-JSON serialization failed: {}", e.getMessage());
            return "[]";
        }
    }
}
