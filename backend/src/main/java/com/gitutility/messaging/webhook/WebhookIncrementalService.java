package com.gitutility.messaging.webhook;

import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.FailoverPeerErrors;
import com.gitutility.service.PairTipEchoService;
import com.gitutility.service.FailoverService;
import com.gitutility.service.PairLeaseBusyException;
import com.gitutility.service.PairLeaseService;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.RefInterestPolicy;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.RepoMappingService;
import com.gitutility.service.SyncLaneRouter;
import com.gitutility.service.WebSocketNotificationService;
import com.gitutility.service.WebhookIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Runs one normalized git event as an incremental mirror on the caller thread.
 * A push or delete is an echo only when {@link PairTipEchoService} sees that result on the other repo.
 */
@Service
@ConditionalOnExpression("'${git-utility.webhook-bus.provider:off}' == 'kafka' or '${git-utility.webhook-bus.provider:off}' == 'rabbitmq'")
@RequiredArgsConstructor
@Slf4j
public class WebhookIncrementalService {

    static final String LISTENER_ID = "webhookIncrementalBus";
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final RepoMappingRepository mappingRepository;
    private final SyncJobRepository syncJobRepository;
    private final UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    private final UnmappedWebhookRetention unmappedWebhookRetention;
    private final RefOriginService refOriginService;
    private final RefInterestPolicy refInterestPolicy;
    private final QueueConsumerService queueConsumerService;
    private final PairLeaseService pairLeaseService;
    private final WebhookEventPublisher publisher;
    private final WebSocketNotificationService webSocketNotificationService;

    private WebhookIngestionService webhookIngestionService;
    private FailoverService failoverService;
    private PairTipEchoService pairTipEchoService;

    @Autowired(required = false)
    public void setWebhookIngestionService(@Lazy WebhookIngestionService webhookIngestionService) {
        this.webhookIngestionService = webhookIngestionService;
    }

    @Autowired(required = false)
    public void setFailoverService(@Lazy FailoverService failoverService) {
        this.failoverService = failoverService;
    }

    @Autowired(required = false)
    public void setPairTipEchoService(PairTipEchoService pairTipEchoService) {
        this.pairTipEchoService = pairTipEchoService;
    }

    @Value("${git-utility.queue.max-retry-attempts:3}")
    private int maxAttempts;

    public void handle(IncrementalGitEvent event) {
        if (event == null || event.getRepoUrl() == null || event.getRepoUrl().isBlank()) {
            publisher.deadLetter(event == null ? new IncrementalGitEvent() : event, "Missing repoUrl");
            return;
        }
        try {
            dispatch(event, true);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            retryOrDeadLetter(event, e);
        }
    }

    /**
     * Runs a stored poison event on this process. A failure stays on the caller’s row.
     * This path does not publish the event back onto the bus.
     *
     * @return null when the event was applied or intentionally skipped; otherwise why it stayed poison
     */
    public String replay(IncrementalGitEvent event) {
        if (event == null || event.getRepoUrl() == null || event.getRepoUrl().isBlank()) {
            return "Missing repoUrl";
        }
        try {
            dispatch(event, false);
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        }
    }

    private void dispatch(IncrementalGitEvent event, boolean fromBus) throws Exception {
        String eventType = event.getEventType() == null ? "push" : event.getEventType().trim().toLowerCase();
        if (isMetadataEvent(eventType)) {
            if (webhookIngestionService == null || event.getRawPayload() == null || event.getRawPayload().isBlank()) {
                discard(event, "METADATA_PAYLOAD", "Metadata event has no raw payload");
                return;
            }
            RepoMapping metaMapping = webhookIngestionService.findMappingForPayload(event.getRawPayload());
            if (metaMapping != null && failoverService != null && failoverService.shouldPark(metaMapping)) {
                failoverService.park(metaMapping, event, "PEER_UNREACHABLE");
                return;
            }
            webhookIngestionService.ingestMetadataPayload(event.getRawPayload());
            return;
        }
        String ref = event.getRef() == null ? "" : event.getRef().trim();
        String branch = branchOf(ref);
        boolean deleted = "delete".equals(eventType)
                || (!"create".equals(eventType) && isZero(event.getAfterSha()));
        String repoPath = repoPath(event.getRepoUrl());
        List<RepoMapping> matches = mappingRepository.findActiveMatchingRepo(event.getRepoUrl(), repoPath);
        if (matches.isEmpty()) {
            discard(event, "UNMAPPED_REPOSITORY", "No active mirror pair for " + event.getRepoUrl());
            return;
        }
        RepoMapping mapping = matches.get(0);
        if (failoverService != null && failoverService.shouldPark(mapping)) {
            failoverService.park(mapping, event, "PEER_UNREACHABLE");
            return;
        }

        if (refInterestPolicy != null && !refInterestPolicy.shouldEnqueuePushWebhook(mapping, branch)) {
            String reason = refInterestPolicy.isEphemeralAutomationBranch(branch)
                    ? RefInterestPolicy.DISCARD_EPHEMERAL
                    : RefInterestPolicy.DISCARD_PATTERN;
            discard(event, reason, "Branch '" + branch + "' skipped by ref interest policy");
            return;
        }

        boolean inboundIsB = RepoMappingService.sameRepo(event.getRepoUrl(), mapping.getRepoBUrl());
        if (inboundIsB && mapping.getSyncDirection() == SyncDirection.UNIDIRECTIONAL_A_TO_B) {
            discard(event, "DIRECTION_IGNORED", "Mapping is unidirectional A -> B");
            return;
        }
        if (!inboundIsB && mapping.getSyncDirection() == SyncDirection.UNIDIRECTIONAL_B_TO_A) {
            discard(event, "DIRECTION_IGNORED", "Mapping is unidirectional B -> A");
            return;
        }
        String sourceRepo = inboundIsB ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        String targetRepo = inboundIsB ? mapping.getRepoAUrl() : mapping.getRepoBUrl();
        PairSide inboundSide = inboundIsB ? PairSide.B : PairSide.A;
        String priorJobId = event.getJobId();

        if (deleted && RefOriginService.isProtectedTrunk(branch)) {
            discard(event, "PROTECTED_TRUNK_DELETE", "Refusing to propagate delete of protected branch " + branch);
            return;
        }
        if (pairTipEchoService != null && deleted && pairTipEchoService.deleteEcho(mapping, inboundIsB, ref)) {
            discard(event, "LOOP_DETECTED_SYSTEM_ECHO", "Other repository already has no " + ref);
            return;
        }
        if (pairTipEchoService != null && !deleted && pairTipEchoService.pushEcho(mapping, inboundIsB, ref, event.getAfterSha())) {
            discard(event, "LOOP_DETECTED_SYSTEM_ECHO", "Other repository already has " + ref + " at this tip");
            return;
        }
        if (inboundIsB && RefOriginService.isSyntheticForkPrHead(branch)) {
            discard(event, "DEST_SYNTHETIC_FORK_HEAD", "Fork PR head is not reverse-synced");
            return;
        }
        if (inboundIsB && RefOriginService.isAutomatedBotBranch(branch)) {
            discard(event, "REPLICA_BOT_BRANCH", "Bot branch on the mirror is not reverse-synced");
            return;
        }
        if (refOriginService != null && refOriginService.shouldBlockReplicaInboundWebhook(mapping, ref, inboundSide)) {
            discard(event, "REPLICA_BACKUP_MIRROR", "Mirror-side changes are not reverse-synced");
            return;
        }
        if (refInterestPolicy != null && !refInterestPolicy.tryAcceptIncrementalEnqueue(mapping.getId(), branch)) {
            discard(event, RefInterestPolicy.DISCARD_COALESCED, "Coalesced incremental for " + branch);
            return;
        }

        SyncJob job = resolveJob(event, mapping, sourceRepo, targetRepo, ref, branch, event.getAfterSha());
        if (job.getStatus() == SyncStatus.SUCCESS
                || job.getStatus() == SyncStatus.SKIPPED
                || job.getStatus() == SyncStatus.CANCELLED
                || job.getStatus() == SyncStatus.CONFLICT_ISOLATED) {
            log.info("Incremental event for job {} already {}, acknowledging", job.getId(), job.getStatus());
            return;
        }

        try {
            pairLeaseService.acquire(mapping.getId(), job.getId());
        } catch (PairLeaseBusyException busy) {
            if (!fromBus) {
                if (priorJobId == null || priorJobId.isBlank()) {
                    syncJobRepository.delete(job);
                    event.setJobId(null);
                }
                throw busy;
            }
            log.info("Pair {} lease busy, re-queueing incremental event: {}", mapping.getId(), busy.getMessage());
            job.setStatus(SyncStatus.QUEUED);
            syncJobRepository.save(job);
            event.setJobId(job.getId());
            publisher.publish(event);
            return;
        }

        SyncEventMessage message = SyncEventMessage.builder()
                .messageId(event.getDeliveryId() == null ? UUID.randomUUID().toString() : event.getDeliveryId())
                .jobId(job.getId())
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepoUrl(sourceRepo)
                .targetRepoUrl(targetRepo)
                .tokenA(mapping.getTokenA())
                .tokenB(mapping.getTokenB())
                .sourceCredentialId(same(sourceRepo, mapping.getRepoAUrl())
                        ? mapping.getSourceCredentialId() : mapping.getTargetCredentialId())
                .targetCredentialId(same(sourceRepo, mapping.getRepoAUrl())
                        ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId())
                .sourceInstallationId(same(sourceRepo, mapping.getRepoAUrl())
                        ? mapping.getSourceInstallationId() : mapping.getTargetInstallationId())
                .targetInstallationId(same(sourceRepo, mapping.getRepoAUrl())
                        ? mapping.getTargetInstallationId() : mapping.getSourceInstallationId())
                .ref(ref)
                .branch(branch)
                .beforeSha(event.getBeforeSha())
                .afterSha(event.getAfterSha())
                .triggerType(TriggerType.WEBHOOK)
                .attemptCount(event.attemptOrOne())
                .maxAttempts(Math.max(1, maxAttempts))
                .enqueuedAt(Instant.now())
                .traceId(event.getDeliveryId())
                .build();
        try {
            queueConsumerService.consumeSyncEvent(message, SyncLaneRouter.LANE_INCREMENTAL, LISTENER_ID);
        } catch (Exception e) {
            if (failoverService != null && FailoverPeerErrors.looksUnreachable(e)) {
                failoverService.markUnreachable(mapping, inboundSide == PairSide.B ? PairSide.A : PairSide.B, e.getMessage());
                failoverService.park(mapping, event, "PEER_UNREACHABLE");
                return;
            }
            if (!fromBus) {
                throw e;
            }
            retryOrDeadLetter(event, e);
        }
    }

    private void retryOrDeadLetter(IncrementalGitEvent event, Exception e) {
        log.warn("Incremental webhook event failed for {}: {}", event.getRepoUrl(), e.getMessage());
        if (event.attemptOrOne() >= Math.max(1, maxAttempts)) {
            publisher.deadLetter(event, e.getMessage());
            return;
        }
        event.setAttempt(event.attemptOrOne() + 1);
        event.setError(e.getMessage());
        publisher.publish(event);
    }

    private SyncJob resolveJob(IncrementalGitEvent event, RepoMapping mapping,
                               String sourceRepo, String targetRepo,
                               String ref, String branch, String afterSha) {
        if (event.getJobId() != null && !event.getJobId().isBlank()) {
            SyncJob existing = syncJobRepository.findById(event.getJobId()).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        SyncJob job = SyncJob.builder()
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepo(sourceRepo)
                .targetRepo(targetRepo)
                .ref(ref)
                .branch(branch)
                .commitSha(afterSha)
                .status(SyncStatus.QUEUED)
                .triggerType(TriggerType.WEBHOOK)
                .createdAt(Instant.now())
                .queueMessageId(event.getDeliveryId())
                .build();
        job = syncJobRepository.save(job);
        event.setJobId(job.getId());
        webSocketNotificationService.notifyJobUpdated(job);
        return job;
    }

    private void discard(IncrementalGitEvent event, String reason, String details) {
        log.info("Incremental event skipped for {} ({}): {}", event.getRepoUrl(), reason, details);
        try {
            UnmappedWebhookEvent row = UnmappedWebhookEvent.builder()
                    .provider(event.getProvider() == null ? "git" : event.getProvider())
                    .repoFullName(repoPath(event.getRepoUrl()))
                    .repoUrl(event.getRepoUrl())
                    .eventType(event.getEventType() == null ? "push" : event.getEventType())
                    .branch(branchOf(event.getRef()))
                    .commitSha(event.getAfterSha())
                    .discardReason(reason)
                    .details(details)
                    .receivedAt(Instant.now())
                    .build();
            unmappedWebhookRetention.stamp(row);
            unmappedWebhookEventRepository.save(row);
            webSocketNotificationService.notifyUnmappedWebhookReceived(row);
        } catch (Exception e) {
            log.warn("Failed to record skipped incremental event: {}", e.getMessage());
        }
    }

    static String branchOf(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith("refs/heads/")) {
            return trimmed.substring("refs/heads/".length());
        }
        return trimmed;
    }

    static String repoPath(String url) {
        String key = RepoMappingService.normalizeRepoKey(url);
        int slash = key.indexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    private static boolean isMetadataEvent(String eventType) {
        return switch (eventType) {
            case "pull_request", "release", "status", "check_run" -> true;
            default -> false;
        };
    }

    private static boolean isZero(String sha) {
        return sha == null || sha.isBlank() || ZERO_SHA.equals(sha.trim());
    }

    private static boolean same(String left, String right) {
        return RepoMappingService.sameRepo(left, right);
    }
}
