package com.gitutility.bdd;

import com.gitutility.messaging.webhook.WebhookEventPublisher;
import com.gitutility.messaging.webhook.WebhookIncrementalService;
import com.gitutility.messaging.webhook.kafka.KafkaWebhookOps;
import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.PairLeaseService;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.RefInterestPolicy;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.SystemEngineConfigService;
import com.gitutility.service.UnmappedWebhookRetention;
import com.gitutility.service.WebSocketNotificationService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PoisonReplaySteps {

    private static final Instant RECEIVED = Instant.parse("2026-09-01T00:00:00Z");
    private static final int TTL_DAYS = 7;

    private final JsonMapper json = JsonMapper.builder().build();
    private MemoryUnmappedEvents events;
    private RepoMappingRepository mappings;
    private WebhookEventPublisher publisher;
    private UnmappedWebhookRetention retention;
    private KafkaWebhookOps ops;
    private UnmappedWebhookEvent poison;
    private UnmappedWebhookEvent discarded;

    @Before("@poison-replay")
    public void reset() {
        events = new MemoryUnmappedEvents();
        mappings = mock(RepoMappingRepository.class);
        publisher = mock(WebhookEventPublisher.class);
        SystemEngineConfigService config = mock(SystemEngineConfigService.class);
        when(config.unmappedWebhookTtlDays()).thenReturn(TTL_DAYS);
        retention = new UnmappedWebhookRetention(events, config);
        WebhookIncrementalService incremental = new WebhookIncrementalService(
                mappings,
                mock(SyncJobRepository.class),
                events,
                retention,
                mock(RefOriginService.class),
                mock(RefInterestPolicy.class),
                mock(QueueConsumerService.class),
                mock(PairLeaseService.class),
                publisher,
                mock(WebSocketNotificationService.class));
        ops = new KafkaWebhookOps(
                mock(KafkaProperties.class),
                incremental,
                retention,
                events,
                json,
                mock(KafkaListenerEndpointRegistry.class));
        poison = null;
        discarded = null;
    }

    @Given("a stored poison event for {string}")
    public void storedPoison(String repoUrl) throws Exception {
        IncrementalGitEvent event = IncrementalGitEvent.builder()
                .provider("github")
                .repoUrl(repoUrl)
                .ref("refs/heads/main")
                .afterSha("abc123")
                .eventType("push")
                .deliveryId("delivery-poison")
                .build();
        poison = events.save(UnmappedWebhookEvent.builder()
                .discardReason("KAFKA_POISON")
                .repoUrl(repoUrl)
                .eventType("push")
                .payloadJson(json.writeValueAsString(event))
                .receivedAt(RECEIVED)
                .expiresAt(null)
                .build());
    }

    @Given("replaying that event succeeds")
    public void replaySucceeds() {
        when(mappings.findActiveMatchingRepo(any(), any())).thenReturn(List.of());
    }

    @Given("replaying that event fails")
    public void replayFails() {
        when(mappings.findActiveMatchingRepo(any(), any()))
                .thenThrow(new IllegalStateException("pair store unavailable"));
    }

    @Given("an unreplayed poison row that already has an expiry")
    public void poisonWithExpiry() {
        poison = events.save(UnmappedWebhookEvent.builder()
                .discardReason("KAFKA_POISON")
                .repoUrl("https://github.com/acme/origin.git")
                .receivedAt(RECEIVED)
                .expiresAt(RECEIVED.plus(TTL_DAYS, ChronoUnit.DAYS))
                .build());
    }

    @Given("an unreplayed poison row")
    public void plainPoison() {
        poison = events.save(UnmappedWebhookEvent.builder()
                .discardReason("KAFKA_POISON")
                .repoUrl("https://github.com/acme/origin.git")
                .receivedAt(RECEIVED)
                .expiresAt(null)
                .build());
    }

    @Given("a discarded webhook row with no expiry")
    public void discardedWithoutExpiry() {
        discarded = events.save(UnmappedWebhookEvent.builder()
                .discardReason("LOOP_DETECTED_SYSTEM_ECHO")
                .repoUrl("https://github.com/acme/origin.git")
                .receivedAt(RECEIVED)
                .expiresAt(null)
                .build());
    }

    @When("the operator replays stored poison")
    public void replayStored() {
        ops.redrive(10);
    }

    @When("that poison row is stamped")
    public void stampPoison() {
        retention.stamp(poison);
    }

    @When("startup stamps missing webhook expiry")
    public void backfill() {
        retention.backfillMissing();
    }

    @Then("the poison row is {string}")
    public void poisonReason(String reason) {
        assertEquals(reason, poison.getDiscardReason());
    }

    @Then("the poison row expires {int} days after it was received")
    public void poisonExpiresAfter(int days) {
        assertEquals(RECEIVED.plus(days, ChronoUnit.DAYS), poison.getExpiresAt());
    }

    @Then("the poison row has no expiry")
    public void poisonHasNoExpiry() {
        assertNull(poison.getExpiresAt());
    }

    @Then("the discarded row has an expiry")
    public void discardedHasExpiry() {
        assertNotNull(discarded.getExpiresAt());
        assertEquals(RECEIVED.plus(TTL_DAYS, ChronoUnit.DAYS), discarded.getExpiresAt());
    }

    @Then("the stored event was processed on this process")
    public void processedInProcess() {
        assertEquals(1, events.findByDiscardReasonOrderByReceivedAtAsc("UNMAPPED_REPOSITORY").size());
    }

    @Then("the event was not published back to the bus")
    public void notPublished() {
        verify(publisher, never()).publish(any());
        verify(publisher, never()).deadLetter(any(), any());
    }

    private static final class MemoryUnmappedEvents implements UnmappedWebhookEventRepository {
        private final List<UnmappedWebhookEvent> rows = new ArrayList<>();
        private int nextId = 1;

        @Override
        public List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc() {
            return findAllByOrderByReceivedAtDesc();
        }

        @Override
        public List<UnmappedWebhookEvent> findByDiscardReasonOrderByReceivedAtAsc(String discardReason) {
            return rows.stream()
                    .filter(row -> discardReason.equals(row.getDiscardReason()))
                    .sorted(Comparator.comparing(UnmappedWebhookEvent::getReceivedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }

        @Override
        public List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc() {
            return rows.stream()
                    .sorted(Comparator.comparing(UnmappedWebhookEvent::getReceivedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            return 0;
        }

        @Override
        public UnmappedWebhookEvent save(UnmappedWebhookEvent entity) {
            if (entity.getId() == null) {
                entity.setId("row-" + nextId++);
            }
            if (!rows.contains(entity)) {
                rows.add(entity);
            }
            return entity;
        }

        @Override
        public List<UnmappedWebhookEvent> saveAll(Iterable<UnmappedWebhookEvent> entities) {
            List<UnmappedWebhookEvent> saved = new ArrayList<>();
            for (UnmappedWebhookEvent entity : entities) {
                saved.add(save(entity));
            }
            return saved;
        }

        @Override
        public Optional<UnmappedWebhookEvent> findById(String id) {
            return rows.stream().filter(row -> id.equals(row.getId())).findFirst();
        }

        @Override
        public boolean existsById(String id) {
            return findById(id).isPresent();
        }

        @Override
        public List<UnmappedWebhookEvent> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public void delete(UnmappedWebhookEvent entity) {
            rows.remove(entity);
        }

        @Override
        public void deleteById(String id) {
            rows.removeIf(row -> id.equals(row.getId()));
        }

        @Override
        public void deleteAll() {
            rows.clear();
        }
    }
}
