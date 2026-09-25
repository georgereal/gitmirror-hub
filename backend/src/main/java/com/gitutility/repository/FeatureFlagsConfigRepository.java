package com.gitutility.repository;

import com.gitutility.model.entity.FeatureFlagsConfig;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for the singleton feature-toggles row.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface FeatureFlagsConfigRepository {

    Optional<FeatureFlagsConfig> findTopByOrderByIdAsc();

    FeatureFlagsConfig save(FeatureFlagsConfig entity);

    List<FeatureFlagsConfig> saveAll(Iterable<FeatureFlagsConfig> entities);

    Optional<FeatureFlagsConfig> findById(String id);

    boolean existsById(String id);

    List<FeatureFlagsConfig> findAll();

    long count();

    void delete(FeatureFlagsConfig entity);

    void deleteById(String id);

    void deleteAll();
}
