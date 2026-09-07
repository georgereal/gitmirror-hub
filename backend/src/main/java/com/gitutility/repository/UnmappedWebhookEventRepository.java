package com.gitutility.repository;

import com.gitutility.model.entity.UnmappedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface UnmappedWebhookEventRepository extends JpaRepository<UnmappedWebhookEvent, Long> {

    List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc();

    List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM UnmappedWebhookEvent u WHERE u.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
