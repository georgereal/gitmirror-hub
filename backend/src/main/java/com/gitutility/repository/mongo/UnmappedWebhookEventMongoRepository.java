package com.gitutility.repository.mongo;

import com.gitutility.model.entity.UnmappedWebhookEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * MongoDB implementation backing the UnmappedWebhookEventRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 * Retention cleanup (deleteOlderThan) lives in the store via MongoTemplate.
 */
public interface UnmappedWebhookEventMongoRepository extends MongoRepository<UnmappedWebhookEvent, String> {

    List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc();

    List<UnmappedWebhookEvent> findByDiscardReasonOrderByReceivedAtAsc(String discardReason);

    List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc();
}
