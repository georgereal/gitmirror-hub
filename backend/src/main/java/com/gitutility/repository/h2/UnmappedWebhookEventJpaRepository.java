package com.gitutility.repository.h2;

import com.gitutility.model.entity.UnmappedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface UnmappedWebhookEventJpaRepository extends JpaRepository<UnmappedWebhookEvent, String> {

    List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc();

    List<UnmappedWebhookEvent> findByDiscardReasonOrderByReceivedAtAsc(String discardReason);

    List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            DELETE FROM UnmappedWebhookEvent u
            WHERE u.receivedAt < :cutoff
              AND u.discardReason <> 'KAFKA_POISON'
            """)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
