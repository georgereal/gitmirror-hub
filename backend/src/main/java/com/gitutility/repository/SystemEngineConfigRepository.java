package com.gitutility.repository;

import com.gitutility.model.entity.SystemEngineConfig;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for the singleton system-engine configuration row.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface SystemEngineConfigRepository {

    Optional<SystemEngineConfig> findTopByOrderByIdAsc();

    SystemEngineConfig save(SystemEngineConfig entity);

    List<SystemEngineConfig> saveAll(Iterable<SystemEngineConfig> entities);

    Optional<SystemEngineConfig> findById(String id);

    boolean existsById(String id);

    List<SystemEngineConfig> findAll();

    long count();

    void delete(SystemEngineConfig entity);

    void deleteById(String id);

    void deleteAll();
}
