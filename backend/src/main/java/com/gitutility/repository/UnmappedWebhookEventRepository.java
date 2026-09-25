package com.gitutility.repository;

import com.gitutility.model.entity.UnmappedWebhookEvent;

import java.time.Instant;
import java.util.List;

/**
 * Store facade for discarded webhook audit records.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface UnmappedWebhookEventRepository {

    List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc();

    List<UnmappedWebhookEvent> findByDiscardReasonOrderByReceivedAtAsc(String discardReason);

    List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc();

    int deleteOlderThan(Instant cutoff);

    UnmappedWebhookEvent save(UnmappedWebhookEvent entity);

    List<UnmappedWebhookEvent> saveAll(Iterable<UnmappedWebhookEvent> entities);

    java.util.Optional<UnmappedWebhookEvent> findById(String id);

    boolean existsById(String id);

    List<UnmappedWebhookEvent> findAll();

    long count();

    void delete(UnmappedWebhookEvent entity);

    void deleteById(String id);

    void deleteAll();
}
