package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.messaging.webhook.WebhookIncrementalService;
import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.service.UnmappedWebhookRetention;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@WebhookBusConditions.OnKafka
@Slf4j
public class KafkaWebhookOps {

    private final KafkaProperties kafkaProperties;
    private final WebhookIncrementalService webhookIncrementalService;
    private final UnmappedWebhookRetention unmappedWebhookRetention;
    private final UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaListenerEndpointRegistry registry;

    @Value("${git-utility.webhook-bus.kafka.incremental-topic}")
    private String topic;

    @Value("${git-utility.webhook-bus.kafka.group-id}")
    private String groupId;

    private static final long BROKER_CACHE_MS = 15_000L;
    /** Drop the admin connection after the Kafka page has been quiet this long. */
    private static final long ADMIN_IDLE_MS = 300_000L;
    private static final long RPC_TIMEOUT_SEC = 10L;

    private final Object brokerLock = new Object();
    private AdminClient admin;
    private Map<String, Object> brokerCache;
    private long brokerCacheAt;
    private long lastAdminUse;

    public KafkaWebhookOps(
            KafkaProperties kafkaProperties,
            WebhookIncrementalService webhookIncrementalService,
            UnmappedWebhookRetention unmappedWebhookRetention,
            UnmappedWebhookEventRepository unmappedWebhookEventRepository,
            ObjectMapper objectMapper,
            KafkaListenerEndpointRegistry registry) {
        this.kafkaProperties = kafkaProperties;
        this.webhookIncrementalService = webhookIncrementalService;
        this.unmappedWebhookRetention = unmappedWebhookRetention;
        this.unmappedWebhookEventRepository = unmappedWebhookEventRepository;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    public Map<String, Object> status(boolean fresh) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        body.put("groupId", groupId);
        body.put("paused", listenerPaused());
        List<UnmappedWebhookEvent> failures = unmappedWebhookEventRepository
                .findByDiscardReasonOrderByReceivedAtAsc(KafkaWebhookPublisher.POISON_REASON);
        body.put("storedFailureCount", failures.size());
        body.put("storedFailures", failures.stream()
                .sorted(Comparator.comparing(UnmappedWebhookEvent::getReceivedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(25)
                .map(this::failureView)
                .toList());
        body.putAll(brokerSnapshot(fresh));
        return body;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireIdleAdmin() {
        synchronized (brokerLock) {
            if (admin != null && System.currentTimeMillis() - lastAdminUse > ADMIN_IDLE_MS) {
                admin.close(Duration.ofSeconds(2));
                admin = null;
                brokerCache = null;
            }
        }
    }

    @PreDestroy
    public void closeAdmin() {
        synchronized (brokerLock) {
            if (admin != null) {
                admin.close(Duration.ofSeconds(2));
                admin = null;
            }
        }
    }

    private Map<String, Object> brokerSnapshot(boolean fresh) {
        synchronized (brokerLock) {
            long now = System.currentTimeMillis();
            lastAdminUse = now;
            if (!fresh && brokerCache != null && now - brokerCacheAt < BROKER_CACHE_MS) {
                return new LinkedHashMap<>(brokerCache);
            }
            try {
                Map<String, Object> snapshot = readBroker();
                brokerCache = snapshot;
                brokerCacheAt = now;
                return new LinkedHashMap<>(snapshot);
            } catch (Exception e) {
                String text = errorText(e);
                log.debug("Webhook Kafka lag unavailable: {}", text);
                if (brokerCache != null) {
                    Map<String, Object> stale = new LinkedHashMap<>(brokerCache);
                    stale.put("lagError", text);
                    stale.put("stale", true);
                    return stale;
                }
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("lag", null);
                failed.put("pending", null);
                failed.put("processed", null);
                failed.put("lagError", text);
                return failed;
            }
        }
    }

    private Map<String, Object> readBroker() throws Exception {
        AdminClient client = admin();
        var described = client.describeTopics(TopicCollection.ofTopicNames(List.of(topic)))
                .allTopicNames()
                .get(RPC_TIMEOUT_SEC, TimeUnit.SECONDS);
        var description = described.get(topic);
        if (description == null) {
            throw new IllegalStateException("Topic not found: " + topic);
        }
        List<TopicPartition> parts = description.partitions().stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .sorted(Comparator.comparingInt(TopicPartition::partition))
                .toList();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        for (TopicPartition part : parts) {
            latest.put(part, OffsetSpec.latest());
            earliest.put(part, OffsetSpec.earliest());
        }
        var endOffsets = client.listOffsets(latest).all().get(RPC_TIMEOUT_SEC, TimeUnit.SECONDS);
        var startOffsets = client.listOffsets(earliest).all().get(RPC_TIMEOUT_SEC, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetAndMetadata> committed = client.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(RPC_TIMEOUT_SEC, TimeUnit.SECONDS);
        String groupState = null;
        int memberCount = 0;
        try {
            var groups = client.describeConsumerGroups(List.of(groupId)).all()
                    .get(RPC_TIMEOUT_SEC, TimeUnit.SECONDS);
            var group = groups.get(groupId);
            if (group != null) {
                groupState = String.valueOf(group.state());
                memberCount = group.members().size();
            }
        } catch (Exception e) {
            log.debug("Webhook Kafka group description unavailable: {}", errorText(e));
        }
        long pending = 0;
        long processed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TopicPartition part : parts) {
            long end = endOffsets.get(part).offset();
            long start = startOffsets.get(part).offset();
            OffsetAndMetadata meta = committed.get(part);
            Long committedOffset = meta == null ? null : meta.offset();
            long partPending = committedOffset == null
                    ? Math.max(0, end - start)
                    : Math.max(0, end - committedOffset);
            long partProcessed = committedOffset == null ? 0 : Math.max(0, committedOffset - start);
            pending += partPending;
            processed += partProcessed;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partition", part.partition());
            row.put("start", start);
            row.put("committed", committedOffset);
            row.put("end", end);
            row.put("pending", partPending);
            row.put("processed", partProcessed);
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lag", pending);
        body.put("pending", pending);
        body.put("processed", processed);
        body.put("partitionCount", parts.size());
        body.put("groupState", groupState);
        body.put("memberCount", memberCount);
        body.put("sampledAt", Instant.now().toString());
        body.put("partitions", rows);
        return body;
    }

    private AdminClient admin() {
        if (admin == null) {
            Map<String, Object> props = new HashMap<>(kafkaProperties.buildAdminProperties());
            props.put(AdminClientConfig.CLIENT_ID_CONFIG, groupId + "-admin");
            props.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, (int) ADMIN_IDLE_MS);
            admin = AdminClient.create(props);
        }
        return admin;
    }

    private static String errorText(Exception e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            return "Timed out reading topic offsets from Kafka";
        }
        String message = e.getMessage();
        Throwable cause = e.getCause();
        if ((message == null || message.isBlank()) && cause != null) {
            message = cause.getMessage();
        }
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private boolean listenerPaused() {
        MessageListenerContainer container = registry.getListenerContainer(KafkaWebhookListener.LISTENER_ID);
        return container != null && (container.isPauseRequested() || container.isContainerPaused());
    }

    private Map<String, Object> failureView(UnmappedWebhookEvent row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("provider", row.getProvider());
        view.put("repoUrl", row.getRepoUrl());
        view.put("repoFullName", row.getRepoFullName());
        view.put("branch", row.getBranch());
        view.put("commitSha", row.getCommitSha());
        view.put("eventType", row.getEventType());
        view.put("details", row.getDetails());
        view.put("receivedAt", row.getReceivedAt() == null ? null : row.getReceivedAt().toString());
        return view;
    }

    /**
     * Processes stored poison rows on this process. A row is marked replayed only after that run
     * finishes. Failures stay {@code KAFKA_POISON}. Nothing is published back onto the topic.
     */
    public int redrive(int limit) {
        int cap = Math.max(1, Math.min(limit, 100));
        List<UnmappedWebhookEvent> rows = unmappedWebhookEventRepository
                .findByDiscardReasonOrderByReceivedAtAsc(KafkaWebhookPublisher.POISON_REASON);
        int attempted = 0;
        int moved = 0;
        for (UnmappedWebhookEvent row : rows) {
            if (attempted >= cap) {
                break;
            }
            IncrementalGitEvent event = readEvent(row.getPayloadJson());
            if (event == null || event.getRepoUrl() == null || event.getRepoUrl().isBlank()) {
                continue;
            }
            attempted++;
            event.setAttempt(1);
            event.setError(null);
            String error = webhookIncrementalService.replay(event);
            if (error != null) {
                row.setDetails(error);
                try {
                    row.setPayloadJson(objectMapper.writeValueAsString(event));
                } catch (Exception ignored) {
                }
                unmappedWebhookEventRepository.save(row);
                log.warn("Poison replay stayed stored for {}: {}", row.getRepoUrl(), error);
                continue;
            }
            row.setDiscardReason(KafkaWebhookPublisher.POISON_REPLAYED);
            unmappedWebhookRetention.stamp(row);
            unmappedWebhookEventRepository.save(row);
            moved++;
        }
        return moved;
    }

    private IncrementalGitEvent readEvent(String payload) {
        if (payload == null || payload.isBlank() || !payload.trim().startsWith("{")) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, IncrementalGitEvent.class);
        } catch (Exception e) {
            log.debug("Stored Kafka failure is not an incremental event: {}", e.getMessage());
            return null;
        }
    }
}
