package com.gitutility.service;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueueProducerService {

    private final SyncEventBus syncEventBus;
    private final SyncJobRepository syncJobRepository;
    private final WebSocketNotificationService webSocketNotificationService;

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

        syncEventBus.publish(message);
        webSocketNotificationService.notifyJobUpdated(job);
    }

    /**
     * Re-publish an existing sync event (e.g. pair lease busy on another pod).
     * Keeps the same job id so operators do not see a duplicate SyncJob row.
     */
    public void republishSyncEvent(SyncEventMessage message) {
        syncEventBus.republish(message);
    }

    private static boolean sameRepo(String left, String right) {
        return RepoMappingService.sameRepo(left, right);
    }
}
