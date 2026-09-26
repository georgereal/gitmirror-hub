package com.gitutility.service;

import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Stamps the discarded-webhook TTL ceiling onto rows that are allowed to expire.
 * Unreplayed Kafka poison rows keep a null {@code expiresAt}.
 */
@Service
@Slf4j
public class UnmappedWebhookRetention {

    public static final String POISON = "KAFKA_POISON";

    private final UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    private final SystemEngineConfigService systemEngineConfigService;

    public UnmappedWebhookRetention(
            UnmappedWebhookEventRepository unmappedWebhookEventRepository,
            @Lazy SystemEngineConfigService systemEngineConfigService) {
        this.unmappedWebhookEventRepository = unmappedWebhookEventRepository;
        this.systemEngineConfigService = systemEngineConfigService;
    }

    public void stamp(UnmappedWebhookEvent row) {
        if (row == null) {
            return;
        }
        if (POISON.equals(row.getDiscardReason())) {
            row.setExpiresAt(null);
            return;
        }
        Instant received = row.getReceivedAt() == null ? Instant.now() : row.getReceivedAt();
        row.setExpiresAt(received.plus(systemEngineConfigService.unmappedWebhookTtlDays(), ChronoUnit.DAYS));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissing() {
        int stamped = apply(false);
        if (stamped > 0) {
            log.info("Stamped webhook TTL expiry on {} discarded rows that had none", stamped);
        }
    }

    /** Rewrites expiry on rows that already have one after the TTL ceiling changes. */
    public int rewriteStamped() {
        int stamped = apply(true);
        if (stamped > 0) {
            log.info("Rewrote webhook TTL expiry on {} discarded rows", stamped);
        }
        return stamped;
    }

    private int apply(boolean requireExisting) {
        int days = systemEngineConfigService.unmappedWebhookTtlDays();
        int stamped = 0;
        for (UnmappedWebhookEvent row : unmappedWebhookEventRepository.findAll()) {
            if (row == null || POISON.equals(row.getDiscardReason())) {
                continue;
            }
            if (requireExisting && row.getExpiresAt() == null) {
                continue;
            }
            if (!requireExisting && row.getExpiresAt() != null) {
                continue;
            }
            Instant received = row.getReceivedAt() == null ? Instant.now() : row.getReceivedAt();
            row.setExpiresAt(received.plus(days, ChronoUnit.DAYS));
            unmappedWebhookEventRepository.save(row);
            stamped++;
        }
        return stamped;
    }
}
