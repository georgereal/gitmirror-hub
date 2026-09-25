package com.gitutility.repository;

import com.gitutility.model.entity.EchoLedgerEntry;

import java.time.Instant;
import java.util.Optional;

/**
 * Store facade for cross-pod echo markers.
 * Exactly one provider-backed implementation is active: H2 or MongoDB.
 */
public interface EchoLedgerRepository {

    void upsert(String repoKey, String token, Instant expiresAt);

    Optional<EchoLedgerEntry> findById(String id);
}
