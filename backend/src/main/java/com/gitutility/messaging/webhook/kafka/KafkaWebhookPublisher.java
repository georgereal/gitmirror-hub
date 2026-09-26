package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.messaging.webhook.WebhookEventPublisher;
import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.RepoMappingService;
import com.gitutility.service.WebSocketNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Component
@WebhookBusConditions.OnKafka
@Slf4j
public class KafkaWebhookPublisher implements WebhookEventPublisher {

    /** Rows Hub could not apply. Replay reads these from the database. */
    public static final String POISON_REASON = "KAFKA_POISON";

    public static final String POISON_REPLAYED = "KAFKA_POISON_REPLAYED";

    private final KafkaTemplate<String, String> webhookKafkaTemplate;
    private final ObjectMapper objectMapper;
    private final UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    private final WebSocketNotificationService webSocketNotificationService;

    @Value("${git-utility.webhook-bus.kafka.incremental-topic}")
    private String topic;

    public KafkaWebhookPublisher(
            KafkaTemplate<String, String> webhookKafkaTemplate,
            ObjectMapper objectMapper,
            UnmappedWebhookEventRepository unmappedWebhookEventRepository,
            WebSocketNotificationService webSocketNotificationService) {
        this.webhookKafkaTemplate = webhookKafkaTemplate;
        this.objectMapper = objectMapper;
        this.unmappedWebhookEventRepository = unmappedWebhookEventRepository;
        this.webSocketNotificationService = webSocketNotificationService;
    }

    @Override
    public void publish(IncrementalGitEvent event) {
        send(topic, event);
    }

    @Override
    public void deadLetter(IncrementalGitEvent event, String reason) {
        if (event != null && (event.getError() == null || event.getError().isBlank())) {
            event.setError(reason);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            payload = null;
        }
        store(event == null ? null : event.getProvider(),
                event == null ? null : event.getRepoUrl(),
                event == null ? null : event.getRef(),
                event == null ? null : event.getAfterSha(),
                event == null ? null : event.getEventType(),
                reason,
                payload);
    }

    public void deadLetterRaw(String key, String body, String reason) {
        store("git", key, null, null, "push", reason, body);
    }

    @Override
    public String destination() {
        return topic;
    }

    private void send(String destination, IncrementalGitEvent event) {
        try {
            String key = event == null ? null : RepoMappingService.normalizeRepoKey(event.getRepoUrl());
            String json = objectMapper.writeValueAsString(event);
            webhookKafkaTemplate.send(destination, key, json).get();
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish to " + destination + " failed: " + e.getMessage(), e);
        }
    }

    private void store(String provider, String repoUrl, String ref, String afterSha,
                       String eventType, String reason, String payload) {
        try {
            UnmappedWebhookEvent row = UnmappedWebhookEvent.builder()
                    .provider(provider == null || provider.isBlank() ? "git" : provider)
                    .repoFullName(repoPath(repoUrl))
                    .repoUrl(repoUrl)
                    .eventType(eventType == null || eventType.isBlank() ? "push" : eventType)
                    .branch(branchOf(ref))
                    .commitSha(afterSha)
                    .discardReason(POISON_REASON)
                    .expiresAt(null)
                    .details(reason == null ? "Incremental record could not be applied" : reason)
                    .payloadJson(payload)
                    .receivedAt(Instant.now())
                    .build();
            unmappedWebhookEventRepository.save(row);
            webSocketNotificationService.notifyUnmappedWebhookReceived(row);
            log.warn("Stored incremental Kafka failure for {}: {}", repoUrl, reason);
        } catch (Exception e) {
            log.warn("Could not store incremental Kafka failure ({}): {}", reason, e.getMessage());
        }
    }

    private static String branchOf(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith("refs/heads/")) {
            return trimmed.substring("refs/heads/".length());
        }
        return trimmed;
    }

    private static String repoPath(String url) {
        String key = RepoMappingService.normalizeRepoKey(url);
        int slash = key.indexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }
}
