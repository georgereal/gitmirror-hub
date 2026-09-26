package com.gitutility.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gitutility.model.dto.GitHubPushPayload;
import com.gitutility.model.dto.InboundWebhookMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookIngestionService {

    private final RepoMappingRepository mappingRepository;
    private final SyncJobRepository syncJobRepository;
    private final QueueProducerService queueProducerService;
    private final DedupLedgerService dedupLedgerService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final ObjectMapper objectMapper;
    private final PullRequestSyncService pullRequestSyncService;
    private final ReleaseAndStatusSyncService releaseAndStatusSyncService;
    private final UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    private final UnmappedWebhookRetention unmappedWebhookRetention;
    private final SystemEngineConfigService systemEngineConfigService;
    private final RefOriginService refOriginService;
    private final RefInterestPolicy refInterestPolicy;
    private final ScmCredentialService scmCredentialService;

    private MetadataSyncSettingsService metadataSyncSettingsService;
    private PairTipEchoService pairTipEchoService;

    @Autowired(required = false)
    public void setMetadataSyncSettingsService(MetadataSyncSettingsService metadataSyncSettingsService) {
        this.metadataSyncSettingsService = metadataSyncSettingsService;
    }

    @Autowired(required = false)
    public void setPairTipEchoService(PairTipEchoService pairTipEchoService) {
        this.pairTipEchoService = pairTipEchoService;
    }

    /**
     * Process an inbound webhook envelope received from Cloudflare Worker via AMQP.
     */
    public void processInboundMessage(InboundWebhookMessage message) {
        log.info("Processing inbound webhook envelope for provider '{}', mappingId: {}",
                message.getProvider(), message.getMappingId());

        if (message.getRawPayload() == null || message.getRawPayload().isBlank()) {
            log.warn("Discarding empty inbound webhook payload");
            return;
        }

        if (message.getMappingId() != null) {
            RepoMapping mapping = mappingRepository.findById(message.getMappingId()).orElse(null);
            if (mapping == null) {
                log.warn("Repo mapping not found for id: {}", message.getMappingId());
                recordDiscardedEvent(message.getProvider(), "mapping-" + message.getMappingId(), null, "webhook",
                        null, null, null, "UNMAPPED_REPOSITORY",
                        "Repo mapping not found for ID: " + message.getMappingId(), message.getRawPayload());
                return;
            }
            if (!mapping.isActive()) {
                log.info("Mapping {} is inactive. Ignoring inbound webhook.", mapping.getName());
                recordDiscardedEvent(message.getProvider(), mapping.getName(), mapping.getRepoAUrl(), "webhook",
                        null, null, null, "INACTIVE_MAPPING",
                        "Mapping '" + mapping.getName() + "' is configured as inactive", message.getRawPayload());
                return;
            }
            processInboundPayload(mapping, message.getRawPayload(), message.getEventType());
        } else {
            // Match dynamically by repo URL / full name
            try {
                JsonNode root = objectMapper.readTree(message.getRawPayload());
                JsonNode repoNode = root.path("repository");
                if (repoNode.isMissingNode()) {
                    log.warn("No repository info in payload");
                    recordDiscardedEvent(message.getProvider(), "unknown", null, "unknown",
                            null, null, null, "UNSUPPORTED_EVENT",
                            "Payload does not contain repository metadata", message.getRawPayload());
                    return;
                }
                String repoUrl = repoNode.path("clone_url").asText(null);
                String repoFullName = repoNode.path("full_name").asText("unknown");

                String ref = root.path("ref").asText(null);
                String branch = ref != null && ref.startsWith("refs/heads/") ? ref.replace("refs/heads/", "") : null;
                String commitSha = root.path("after").asText(null);
                String sender = root.path("sender").path("login").asText(null);
                String eventType = root.has("pull_request") ? "pull_request" : (root.has("commits") || root.has("after") ? "push" : "webhook");

                List<RepoMapping> matches = mappingRepository.findActiveMatchingRepo(repoUrl, repoFullName);
                if (matches.isEmpty()) {
                    log.info("No active mapping configured for repo: {}", repoFullName);
                    recordDiscardedEvent(message.getProvider(), repoFullName, repoUrl, eventType,
                            sender, branch, commitSha, "UNMAPPED_REPOSITORY",
                            "No active mirror pair configured for repository: " + repoFullName, message.getRawPayload());
                    return;
                }
                processInboundPayload(matches.get(0), message.getRawPayload(), message.getEventType());
            } catch (Exception e) {
                log.error("Failed to parse generic inbound push payload: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Entry for the Kafka incremental bus when the record carries a metadata event body.
     */
    public void ingestMetadataPayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return;
        }
        RepoMapping mapping = findMappingForPayload(rawPayload);
        if (mapping == null) {
            log.info("Metadata webhook has no active pair");
            return;
        }
        processInboundPayload(mapping, rawPayload, null);
    }

    public RepoMapping findMappingForPayload(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode repoNode = root.path("repository");
            String repoUrl = repoNode.path("clone_url").asText(null);
            if (repoUrl == null || repoUrl.isBlank()) {
                repoUrl = repoNode.path("html_url").asText(null);
            }
            String repoFullName = repoNode.path("full_name").asText(null);
            List<RepoMapping> matches = mappingRepository.findActiveMatchingRepo(repoUrl, repoFullName);
            return matches.isEmpty() ? null : matches.get(0);
        } catch (Exception e) {
            log.debug("Could not resolve a pair from webhook payload: {}", e.getMessage());
            return null;
        }
    }

    public ResponseEntity<?> processGithubDelivery(RepoMapping mapping, String eventType, String rawPayload) {
        String type = eventType == null ? "push" : eventType.trim().toLowerCase();
        if ("ping".equals(type)) {
            return ResponseEntity.ok(Map.of("status", "pong"));
        }
        if (isMetadataEvent(type) || "create".equals(type) || "delete".equals(type)) {
            processInboundPayload(mapping, rawPayload, type);
            return ResponseEntity.accepted().body(Map.of("status", "accepted", "event", type));
        }
        if (!"push".equals(type)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Event type is not mirrored: " + type));
        }
        return processPushEvent(mapping, rawPayload);
    }

    public static boolean isMetadataEvent(String eventType) {
        if (eventType == null) {
            return false;
        }
        return switch (eventType.trim().toLowerCase()) {
            case "pull_request", "release", "status", "check_run" -> true;
            default -> false;
        };
    }

    private boolean pullRequestsOn() {
        return metadataSyncSettingsService == null || metadataSyncSettingsService.isPullRequestsEnabled();
    }

    private boolean releasesOn() {
        return metadataSyncSettingsService == null || metadataSyncSettingsService.isReleasesEnabled();
    }

    private boolean ciChecksOn() {
        return metadataSyncSettingsService == null || metadataSyncSettingsService.isCiChecksEnabled();
    }

    private String synthesizeRefEvent(JsonNode root, String eventType) {
        String shortRef = root.path("ref").asText("");
        if (shortRef.isBlank()) {
            return null;
        }
        String refType = root.path("ref_type").asText("branch");
        String fullRef = shortRef.startsWith("refs/")
                ? shortRef
                : ("tag".equals(refType) ? "refs/tags/" : "refs/heads/") + shortRef;
        boolean deleted = "delete".equals(eventType);
        String zero = "0000000000000000000000000000000000000000";
        try {
            var node = objectMapper.createObjectNode();
            node.put("ref", fullRef);
            node.put("before", deleted ? root.path("before").asText(zero) : zero);
            node.put("after", deleted ? zero : "");
            node.put("deleted", deleted);
            if (root.has("repository")) {
                node.set("repository", root.path("repository").deepCopy());
            }
            if (root.has("sender")) {
                node.set("sender", root.path("sender").deepCopy());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Could not normalize {} event: {}", eventType, e.getMessage());
            return null;
        }
    }

    private void processInboundPayload(RepoMapping mapping, String rawPayload, String eventType) {
        String type = eventType == null ? "" : eventType.trim().toLowerCase();
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            if ("create".equals(type) || "delete".equals(type)) {
                String synthesized = synthesizeRefEvent(root, type);
                if (synthesized != null) {
                    processPushEvent(mapping, synthesized);
                }
                return;
            }
            if (root.has("pull_request") || root.has("pullrequest") || "pull_request".equals(type)) {
                String action = root.path("action").asText(root.has("pullrequest") ? "opened" : "opened");
                JsonNode prNode = root.has("pull_request") ? root.path("pull_request") : root.path("pullrequest");
                String inboundRepoUrl = root.path("repository").path("clone_url").asText(null);
                if (inboundRepoUrl == null || inboundRepoUrl.isBlank()) {
                    inboundRepoUrl = root.path("repository").path("links").path("html").path("href").asText(null);
                }
                String headBranch = prNode.path("head").path("ref")
                        .asText(prNode.path("source").path("branch").path("name").asText(null));
                if (refInterestPolicy != null && !refInterestPolicy.shouldHandlePrWebhook(mapping, headBranch)) {
                    String reason = refInterestPolicy.isEphemeralAutomationBranch(headBranch)
                            ? RefInterestPolicy.DISCARD_EPHEMERAL
                            : RefInterestPolicy.DISCARD_PATTERN;
                    recordDiscardedEvent("webhook", mapping.getName(), inboundRepoUrl, "pull_request",
                            null, headBranch, null, reason,
                            "PR head '" + headBranch + "' skipped for live webhook; Smart full sync remains DR catch-up",
                            rawPayload);
                    log.info("Skipped PR webhook for head '{}' on mapping {} ({})", headBranch, mapping.getName(), reason);
                    return;
                }
                String senderLogin = root.path("sender").path("login").asText(null);
                if (isMirrorAppActor(mapping, inboundRepoUrl, senderLogin)) {
                    recordDiscardedEvent("webhook", mapping.getName(), inboundRepoUrl, "pull_request",
                            senderLogin, headBranch, null, "MIRROR_APP_PUSH",
                            "Pull request event was sent by the mirror GitHub App", rawPayload);
                    return;
                }
                long eventPrNumber = prNode.path("number").asLong(prNode.path("id").asLong(0));
                String headSha = shaText(prNode.path("head").path("sha"));
                String mergeSha = shaText(prNode.get("merge_commit_sha"));
                boolean merged = prNode.path("merged").asBoolean(false);
                if (eventPrNumber > 0 && dedupLedgerService.isEchoPullRequest(
                        inboundRepoUrl, eventPrNumber, action, headSha, mergeSha, merged)) {
                    recordDiscardedEvent("webhook", mapping.getName(), inboundRepoUrl, "pull_request",
                            senderLogin, headBranch, mergeSha != null ? mergeSha : headSha, "LOOP_DETECTED_SYSTEM_ECHO",
                            "Pull request #" + eventPrNumber + " " + action + " matches a write this mirror recorded",
                            rawPayload);
                    return;
                }
                if (!pullRequestsOn()) {
                    log.info("Pull request webhook skipped; metadata setting is off");
                    return;
                }
                pullRequestSyncService.handlePrWebhookEvent(mapping, action, prNode, inboundRepoUrl);
                return;
            }
            if (root.has("check_run") || "check_run".equals(type)) {
                if (!ciChecksOn()) {
                    log.info("Check run webhook skipped; metadata setting is off");
                    return;
                }
                JsonNode checkRun = root.path("check_run");
                String checkAction = root.path("action").asText("");
                String checkStatus = checkRun.path("status").asText("");
                if (!"completed".equalsIgnoreCase(checkAction) && !"completed".equalsIgnoreCase(checkStatus)) {
                    log.info("Check run '{}' is still {}; waiting for completed",
                            checkRun.path("name").asText(""), checkStatus);
                    return;
                }
                String inboundRepoUrl = root.path("repository").path("clone_url").asText(null);
                releaseAndStatusSyncService.replicateWebhookCheckRun(mapping, inboundRepoUrl, checkRun);
                return;
            }
            if (root.has("release") || "release".equals(type)) {
                if (!releasesOn()) {
                    log.info("Release webhook skipped; metadata setting is off");
                    return;
                }
                String inboundRepoUrl = root.path("repository").path("clone_url").asText(null);
                releaseAndStatusSyncService.mirrorWebhookRelease(
                        mapping, inboundRepoUrl, root.path("action").asText(""), root.path("release"));
                return;
            }
            if (root.has("state") && root.has("sha") && root.has("context")) {
                String sha = root.path("sha").asText();
                String state = root.path("state").asText();
                String targetUrl = root.path("target_url").asText(null);
                String description = root.path("description").asText(null);
                String context = root.path("context").asText(null);
                if (!ciChecksOn()) {
                    log.info("Commit status webhook skipped; metadata setting is off");
                    return;
                }
                String inboundRepoUrl = root.path("repository").path("clone_url").asText(null);
                releaseAndStatusSyncService.replicateInboundCommitStatus(
                        mapping, inboundRepoUrl, sha, state, targetUrl, description, context);
                return;
            }
            if (root.has("commit_status")) {
                JsonNode cs = root.path("commit_status");
                String sha = cs.path("commit").path("hash").asText();
                String state = cs.path("state").asText();
                String targetUrl = cs.path("url").asText(null);
                String description = cs.path("description").asText(null);
                String context = cs.path("name").asText(null);
                if (!ciChecksOn()) {
                    log.info("Commit status webhook skipped; metadata setting is off");
                    return;
                }
                String inboundRepoUrl = root.path("repository").path("links").path("html").path("href").asText(null);
                releaseAndStatusSyncService.replicateInboundCommitStatus(
                        mapping, inboundRepoUrl, sha, state, targetUrl, description, context);
                return;
            }
        } catch (Exception e) {
            log.debug("Notice parsing webhook payload: {}", e.getMessage());
        }
        processPushEvent(mapping, rawPayload);
    }

    public ResponseEntity<?> processPushEvent(RepoMapping mapping, String rawPayload) {
        try {
            GitHubPushPayload payload = null;
            try {
                payload = objectMapper.readValue(rawPayload, GitHubPushPayload.class);
            } catch (Exception ignored) {}

            String ref = payload != null ? payload.getRef() : null;
            String afterSha = payload != null ? payload.getAfter() : null;
            String beforeSha = payload != null ? payload.getBefore() : null;
            String author = payload != null && payload.getPusher() != null ? payload.getPusher().getName() : "unknown";
            String commitMessage = payload != null && payload.getHeadCommit() != null ? payload.getHeadCommit().getMessage() : null;
            String inboundRepoUrl = payload != null && payload.getRepository() != null ? payload.getRepository().getCloneUrl() : null;
            boolean deleted = payload != null && (payload.isDeleted() || RefOriginService.isDeletedSha(payload.getAfter()));

            // Fallback for Bitbucket push payload
            if (ref == null) {
                try {
                    JsonNode root = objectMapper.readTree(rawPayload);
                    JsonNode changes = root.path("push").path("changes");
                    if (changes.isArray() && !changes.isEmpty()) {
                        JsonNode change = changes.get(0);
                        String branchName = change.path("new").path("name").asText(null);
                        if (branchName != null) {
                            ref = "refs/heads/" + branchName;
                            afterSha = change.path("new").path("target").path("hash").asText();
                            beforeSha = change.path("old").path("target").path("hash").asText(null);
                            commitMessage = change.path("new").path("target").path("message").asText("Bitbucket push to " + branchName);
                            author = change.path("new").path("target").path("author").path("raw").asText("bitbucket");
                            inboundRepoUrl = root.path("repository").path("links").path("html").path("href").asText(null);
                        } else {
                            String oldName = change.path("old").path("name").asText(null);
                            if (oldName != null && !oldName.isBlank()) {
                                ref = "refs/heads/" + oldName;
                                afterSha = "0000000000000000000000000000000000000000";
                                beforeSha = change.path("old").path("target").path("hash").asText(null);
                                commitMessage = "Deleted branch " + oldName;
                                author = "bitbucket";
                                inboundRepoUrl = root.path("repository").path("links").path("html").path("href").asText(null);
                                deleted = true;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (ref == null || !ref.startsWith("refs/heads/")) {
                String repoFull = payload != null && payload.getRepository() != null ? payload.getRepository().getFullName() : mapping.getName();
                String repoUrl = inboundRepoUrl != null ? inboundRepoUrl : mapping.getRepoAUrl();
                recordDiscardedEvent("webhook", repoFull, repoUrl, "push",
                        author, ref, afterSha, "NON_BRANCH_REF",
                        "Ref is not a branch (" + ref + ")", rawPayload);
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Ref is not a branch: " + ref));
            }

            String branch = ref.replace("refs/heads/", "");
            if (deleted) {
                commitMessage = "Deleted branch " + branch;
            } else if (commitMessage == null) {
                commitMessage = (payload != null && payload.getHeadCommit() != null)
                        ? payload.getHeadCommit().getMessage()
                        : "Push event on " + branch;
            }
            if (author == null) {
                author = (payload != null && payload.getPusher() != null)
                        ? payload.getPusher().getName()
                        : (payload != null && payload.getSender() != null ? payload.getSender().getLogin() : "unknown");
            }

            // Agentic / bot / pattern filter — before direction flip so discard reasons stay clear.
            if (refInterestPolicy != null && !refInterestPolicy.shouldEnqueuePushWebhook(mapping, branch)) {
                String reason = refInterestPolicy.isEphemeralAutomationBranch(branch)
                        ? RefInterestPolicy.DISCARD_EPHEMERAL
                        : RefInterestPolicy.DISCARD_PATTERN;
                String detail = reason.equals(RefInterestPolicy.DISCARD_EPHEMERAL)
                        ? "Ephemeral automation branch '" + branch + "' skipped; Smart full sync catches tip churn for DR"
                        : "Branch '" + branch + "' does not match pair branchPattern '" + mapping.getBranchPattern() + "'";
                return skipPush(mapping, mapping.getRepoAUrl(), mapping.getRepoBUrl(), ref, branch, afterSha,
                        commitMessage, author, reason, detail);
            }

            // Determine sync direction and source/destination repo
            String sourceRepo = mapping.getRepoAUrl();
            String targetRepo = mapping.getRepoBUrl();
            boolean inboundIsB = inboundRepoUrl != null && RepoMappingService.sameRepo(inboundRepoUrl, mapping.getRepoBUrl());

            if (inboundIsB) {
                if (mapping.getSyncDirection() == SyncDirection.UNIDIRECTIONAL_A_TO_B) {
                    recordDiscardedEvent("webhook", mapping.getName(), inboundRepoUrl, "push",
                            null, branch, afterSha, "DIRECTION_IGNORED",
                            "Mapping is configured as Unidirectional A -> B only", rawPayload);
                    return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Mapping is configured as Unidirectional A -> B only"));
                }
                sourceRepo = mapping.getRepoBUrl();
                targetRepo = mapping.getRepoAUrl();
            } else {
                if (mapping.getSyncDirection() == SyncDirection.UNIDIRECTIONAL_B_TO_A) {
                    recordDiscardedEvent("webhook", mapping.getName(), inboundRepoUrl, "push",
                            null, branch, afterSha, "DIRECTION_IGNORED",
                            "Mapping is configured as Unidirectional B -> A only", rawPayload);
                    return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Mapping is configured as Unidirectional B -> A only"));
                }
            }

            PairSide inboundSide = inboundIsB ? PairSide.B : PairSide.A;

            if (deleted && RefOriginService.isProtectedTrunk(branch)) {
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        "PROTECTED_TRUNK_DELETE", "Refusing to propagate delete of protected branch " + branch);
            }

            if (pairTipEchoService != null && deleted && pairTipEchoService.deleteEcho(mapping, inboundIsB, ref)) {
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        "LOOP_DETECTED_SYSTEM_ECHO", "Other repository already has no " + ref);
            }

            if (inboundIsB && RefOriginService.isSyntheticForkPrHead(branch)) {
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        "DEST_SYNTHETIC_FORK_HEAD",
                        "Destination-only fork PR head '" + branch + "' is not reverse-synced to origin");
            }

            if (inboundIsB && RefOriginService.isAutomatedBotBranch(branch)) {
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        "REPLICA_BOT_BRANCH",
                        "Automated bot maintenance branch on mirror is not reverse-synced to origin");
            }

            if (refOriginService != null
                    && refOriginService.shouldBlockReplicaInboundWebhook(mapping, ref, inboundSide)) {
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        "REPLICA_BACKUP_MIRROR",
                        "Public→private backup: mirror-side changes are not reverse-synced to upstream");
            }

            if (pairTipEchoService != null && !deleted && pairTipEchoService.pushEcho(mapping, inboundIsB, ref, afterSha)) {
                log.info("Bidirectional loop prevented for commit {} on mapping {}. Peer already has this tip.", afterSha, mapping.getName());
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        "LOOP_DETECTED_SYSTEM_ECHO", "Other repository already has " + ref + " at this tip");
            }

            if (refInterestPolicy != null
                    && !refInterestPolicy.tryAcceptIncrementalEnqueue(mapping.getId(), branch)) {
                return skipPush(mapping, sourceRepo, targetRepo, ref, branch, afterSha, commitMessage, author,
                        RefInterestPolicy.DISCARD_COALESCED,
                        "Coalesced non-trunk push on '" + branch
                                + "'; another incremental job recently accepted for this pair");
            }

            // 2. Create and Enqueue Sync Job
            SyncJob job = SyncJob.builder()
                    .mappingId(mapping.getId())
                    .pairName(mapping.getName())
                    .sourceRepo(sourceRepo)
                    .targetRepo(targetRepo)
                    .ref(ref)
                    .branch(branch)
                    .commitSha(afterSha)
                    .commitMessage(commitMessage)
                    .author(author)
                    .status(SyncStatus.QUEUED)
                    .triggerType(TriggerType.WEBHOOK)
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
                    branch,
                    beforeSha,
                    afterSha,
                    commitMessage,
                    author,
                    TriggerType.WEBHOOK
            );

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "enqueued",
                    "jobId", job.getId(),
                    "mapping", mapping.getName(),
                    "branch", branch,
                    "commitSha", afterSha
            ));

        } catch (Exception e) {
            log.error("Error processing GitHub push event: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to enqueue webhook: " + e.getMessage()));
        }
    }

    private static String shaText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text.trim();
    }

    private boolean isMirrorAppActor(RepoMapping mapping, String repoUrl, String... actors) {
        if (scmCredentialService == null || mapping == null || actors == null) {
            return false;
        }
        boolean inboundIsB = repoUrl != null && RepoMappingService.sameRepo(repoUrl, mapping.getRepoBUrl());
        String credId = inboundIsB ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId();
        if (credId == null || credId.isBlank()) {
            return false;
        }
        try {
            ScmCredential cred = scmCredentialService.require(credId);
            String bot = cred.getBotLogin();
            if (bot == null || bot.isBlank()) {
                return false;
            }
            for (String actor : actors) {
                if (actor != null && bot.equalsIgnoreCase(actor.trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Mirror App actor check skipped: {}", e.getMessage());
        }
        return false;
    }

    private ResponseEntity<?> skipPush(RepoMapping mapping, String sourceRepo, String targetRepo,
                                       String ref, String branch, String afterSha, String commitMessage,
                                       String author, String skipReason, String publicReason) {
        SyncJob skippedJob = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(sourceRepo)
                .targetRepo(targetRepo)
                .ref(ref)
                .branch(branch)
                .commitSha(afterSha)
                .commitMessage(commitMessage)
                .author(author)
                .status(SyncStatus.SKIPPED)
                .skipReason(skipReason)
                .triggerType(TriggerType.WEBHOOK)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .durationMs(0L)
                .createdAt(Instant.now())
                .build();
        syncJobRepository.save(skippedJob);
        webSocketNotificationService.notifyJobUpdated(skippedJob);
        log.info("Skipped webhook for {} on mapping {} ({})", branch, mapping.getName(), skipReason);
        return ResponseEntity.ok(Map.of(
                "status", "skipped",
                "reason", publicReason,
                "commitSha", afterSha != null ? afterSha : ""
        ));
    }

    private void recordDiscardedEvent(String provider, String repoFullName, String repoUrl, String eventType,
                                     String sender, String branch, String commitSha,
                                     String discardReason, String details, String rawPayload) {
        try {
            String commitMsg = null;
            if (rawPayload != null) {
                try {
                    JsonNode root = objectMapper.readTree(rawPayload);
                    if (commitMsg == null && root.has("head_commit")) {
                        commitMsg = root.path("head_commit").path("message").asText(null);
                    }
                    if (sender == null && root.has("sender")) {
                        sender = root.path("sender").path("login").asText(null);
                    }
                } catch (Exception ignored) {}
            }

            UnmappedWebhookEvent event = UnmappedWebhookEvent.builder()
                    .provider(provider != null ? provider : "github")
                    .repoFullName(repoFullName)
                    .repoUrl(repoUrl)
                    .eventType(eventType != null ? eventType : "webhook")
                    .sender(sender)
                    .branch(branch)
                    .commitSha(commitSha)
                    .commitMessage(commitMsg)
                    .discardReason(discardReason)
                    .details(details)
                    .receivedAt(Instant.now())
                    .build();
            unmappedWebhookRetention.stamp(event);

            unmappedWebhookEventRepository.save(event);
            webSocketNotificationService.notifyUnmappedWebhookReceived(event);
            log.info("Recorded unmapped/discarded webhook event for '{}' [{}] (Reason: {})",
                    repoFullName, eventType, discardReason);
        } catch (Exception e) {
            log.warn("Failed to record unmapped webhook event: {}", e.getMessage());
        }
    }

    private Instant lastUnmappedPurgeAt = Instant.EPOCH;

    /**
     * Deletes discarded webhook rows older than the configured purge age.
     * Ticks once a minute and runs only when the configured interval has elapsed.
     * Unreplayed Kafka poison rows are excluded by the store delete.
     */
    @Scheduled(fixedDelay = 60_000)
    public void purgeOldUnmappedEvents() {
        try {
            int intervalMinutes = systemEngineConfigService.unmappedWebhookPurgeIntervalMinutes();
            Instant now = Instant.now();
            if (lastUnmappedPurgeAt.plus(intervalMinutes, ChronoUnit.MINUTES).isAfter(now)) {
                return;
            }
            int purgeDays = systemEngineConfigService.unmappedWebhookPurgeDays();
            Instant cutoff = now.minus(purgeDays, ChronoUnit.DAYS);
            int deleted = unmappedWebhookEventRepository.deleteOlderThan(cutoff);
            lastUnmappedPurgeAt = now;
            if (deleted > 0) {
                log.info("Purged {} discarded webhook events older than {} days (cutoff: {}). Unreplayed Kafka poison rows are kept.",
                        deleted, purgeDays, cutoff);
            }
        } catch (Exception e) {
            log.warn("Failed to purge old unmapped webhook events: {}", e.getMessage());
        }
    }

    public boolean isValidSignature(String payload, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            String expectedHash = signatureHeader.substring("sha256=".length());
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String calculatedHash = HexFormat.of().formatHex(rawHmac);
            return calculatedHash.equalsIgnoreCase(expectedHash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error calculating HMAC-SHA256: {}", e.getMessage());
            return false;
        }
    }
}
