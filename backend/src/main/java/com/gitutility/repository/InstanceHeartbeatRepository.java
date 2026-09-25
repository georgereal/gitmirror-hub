package com.gitutility.repository;

import com.gitutility.model.entity.InstanceHeartbeat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Store facade for fleet heartbeats (natural key: {@code instanceId}).
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface InstanceHeartbeatRepository {

    int deleteOlderThan(Instant cutoff);

    InstanceHeartbeat save(InstanceHeartbeat entity);

    List<InstanceHeartbeat> saveAll(Iterable<InstanceHeartbeat> entities);

    Optional<InstanceHeartbeat> findById(String id);

    boolean existsById(String id);

    List<InstanceHeartbeat> findAll();

    long count();

    void delete(InstanceHeartbeat entity);

    void deleteById(String id);

    void deleteAll();
}
