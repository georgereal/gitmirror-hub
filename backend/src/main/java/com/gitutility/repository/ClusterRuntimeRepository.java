package com.gitutility.repository;

import com.gitutility.model.entity.ClusterRuntime;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for the singleton cluster control-plane row (id = {@code singleton}).
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface ClusterRuntimeRepository {

    ClusterRuntime save(ClusterRuntime entity);

    List<ClusterRuntime> saveAll(Iterable<ClusterRuntime> entities);

    Optional<ClusterRuntime> findById(String id);

    boolean existsById(String id);

    List<ClusterRuntime> findAll();

    long count();

    void delete(ClusterRuntime entity);

    void deleteById(String id);

    void deleteAll();
}
