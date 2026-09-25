package com.gitutility.repository;

import com.gitutility.model.entity.PairLease;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Store facade for multi-pod pair leases (natural key: {@code mappingId}).
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 * Implementations must keep {@link #renewOwned} / {@link #deleteOwned} atomic on owner + expiry.
 */
public interface PairLeaseRepository {

    int deleteOwned(String mappingId, String owner);

    int renewOwned(String mappingId, String owner, String jobId, Instant expiresAt, Instant updatedAt);

    PairLease save(PairLease entity);

    List<PairLease> saveAll(Iterable<PairLease> entities);

    Optional<PairLease> findById(String id);

    boolean existsById(String id);

    List<PairLease> findAll();

    long count();

    void delete(PairLease entity);

    void deleteById(String id);

    void deleteAll();
}
