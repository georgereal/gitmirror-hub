package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueueProducerService {

    private final RabbitTemplate rabbitTemplate;
    private final SyncJobRepository syncJobRepository;
    private final WebSocketNotificationService webSocketNotificationService;

    @Value("${git-utility.queue.exchange:git.sync.exchange}")
    private String exchangeName;

    @Value("${git-utility.queue.routing-key:git.sync.key}")
    private String fullRoutingKey;

    @Value("${git-utility.queue.incremental-routing-key:git.sync.incremental.key}")
    private String incrementalRoutingKey;

    public void enqueueSyncJob(RepoMapping mapping,
                               SyncJob job,
                               String sourceRepo,
                               String targetRepo,
                               String ref,
                               String branch,
                               String beforeSha,
                               String afterSha,
                               String commitMessage,
                               String author,
                               TriggerType triggerType) {
        enqueueSyncJob(mapping, job, sourceRepo, targetRepo, ref, branch, beforeSha, afterSha,
                commitMessage, author, triggerType, false);
    }

    public void enqueueSyncJob(RepoMapping mapping,
                               SyncJob job,
                               String sourceRepo,
                               String targetRepo,
                               String ref,
                               String branch,
                               String beforeSha,
                               String afterSha,
                               String commitMessage,
                               String author,
                               TriggerType triggerType,
                               boolean overwriteFromSource) {
        enqueueSyncJob(mapping, job, sourceRepo, targetRepo, ref, branch, beforeSha, afterSha,
                commitMessage, author, triggerType, overwriteFromSource, false);
    }

    public void enqueueSyncJob(RepoMapping mapping,
                               SyncJob job,
                               String sourceRepo,
                               String targetRepo,
                               String ref,
                               String branch,
                               String beforeSha,
                               String afterSha,
                               String commitMessage,
                               String author,
                               TriggerType triggerType,
                               boolean overwriteFromSource,
                               boolean forceSourceFetch) {

        String messageId = UUID.randomUUID().toString();
        job.setQueueMessageId(messageId);
        syncJobRepository.save(job);

        SyncEventMessage message = SyncEventMessage.builder()
                .messageId(messageId)
                .jobId(job.getId())
                .mappingId(mapping.getId())
                .pairName(mapping.getName())
                .sourceRepoUrl(sourceRepo)
                .targetRepoUrl(targetRepo)
                .tokenA(mapping.getTokenA())
                .tokenB(mapping.getTokenB())
                .sourceCredentialId(sameRepo(sourceRepo, mapping.getRepoAUrl())
                        ? mapping.getSourceCredentialId() : mapping.getTargetCredentialId())
                .targetCredentialId(sameRepo(sourceRepo, mapping.getRepoAUrl())
                        ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId())
                .ref(ref)
                .branch(branch)
                .beforeSha(beforeSha)
                .afterSha(afterSha)
                .commitMessage(commitMessage)
                .author(author)
                .triggerType(triggerType)
                .attemptCount(1)
                .maxAttempts(3)
                .enqueuedAt(Instant.now())
                .traceId(UUID.randomUUID().toString())
                .overwriteFromSource(overwriteFromSource)
                .forceSourceFetch(forceSourceFetch)
                .build();

        String routingKey = SyncLaneRouter.routingKey(ref, branch, fullRoutingKey, incrementalRoutingKey);
        log.info("Publishing sync event to exchange '{}' with routing key '{}' (lane {}) for Job #{} [{}]",
                exchangeName, routingKey, SyncLaneRouter.lane(ref, branch), job.getId(), mapping.getName());

        rabbitTemplate.convertAndSend(exchangeName, routingKey, message, m -> {
            m.getMessageProperties().setMessageId(messageId);
            m.getMessageProperties().setCorrelationId(job.getId().toString());
            m.getMessageProperties().setTimestamp(java.util.Date.from(Instant.now()));
            return m;
        });

        webSocketNotificationService.notifyJobUpdated(job);
    }

    /**
     * Re-publish an existing sync event (e.g. pair lease busy on another pod).
     * Keeps the same job id so operators do not see a duplicate SyncJob row.
     */
    public void republishSyncEvent(SyncEventMessage message) {
        if (message == null || message.getJobId() == null) {
            return;
        }
        String routingKey = SyncLaneRouter.routingKey(
                message.getRef(), message.getBranch(), fullRoutingKey, incrementalRoutingKey);
        String messageId = message.getMessageId() != null ? message.getMessageId() : UUID.randomUUID().toString();
        message.setMessageId(messageId);
        message.setEnqueuedAt(Instant.now());
        log.info("Re-publishing sync event for Job #{} onto lane {} (lease/contention defer)",
                message.getJobId(), SyncLaneRouter.lane(message.getRef(), message.getBranch()));
        rabbitTemplate.convertAndSend(exchangeName, routingKey, message, m -> {
            m.getMessageProperties().setMessageId(messageId);
            m.getMessageProperties().setCorrelationId(message.getJobId().toString());
            m.getMessageProperties().setTimestamp(java.util.Date.from(Instant.now()));
            return m;
        });
    }

    private static boolean sameRepo(String left, String right) {
        return RepoMappingService.sameRepo(left, right);
    }
}
