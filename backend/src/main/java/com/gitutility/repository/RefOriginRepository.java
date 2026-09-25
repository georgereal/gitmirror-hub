package com.gitutility.repository;

import com.gitutility.model.entity.RefOrigin;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for ref-origin bookkeeping (which pair side first pushed a ref).
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface RefOriginRepository {

    Optional<RefOrigin> findByMappingIdAndRefName(String mappingId, String refName);

    RefOrigin save(RefOrigin entity);

    List<RefOrigin> saveAll(Iterable<RefOrigin> entities);

    Optional<RefOrigin> findById(String id);

    boolean existsById(String id);

    List<RefOrigin> findAll();

    long count();

    void delete(RefOrigin entity);

    void deleteById(String id);

    void deleteAll();
}
