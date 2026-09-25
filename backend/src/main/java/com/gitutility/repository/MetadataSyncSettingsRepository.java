package com.gitutility.repository;

import com.gitutility.model.entity.MetadataSyncSettings;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for the singleton metadata-sync settings row.
 * Exactly one provider-backed implementation is active: H2 or MongoDB.
 */
public interface MetadataSyncSettingsRepository {

    Optional<MetadataSyncSettings> findTopByOrderByIdAsc();

    MetadataSyncSettings save(MetadataSyncSettings entity);

    List<MetadataSyncSettings> saveAll(Iterable<MetadataSyncSettings> entities);

    Optional<MetadataSyncSettings> findById(String id);

    boolean existsById(String id);

    List<MetadataSyncSettings> findAll();

    long count();

    void delete(MetadataSyncSettings entity);

    void deleteById(String id);

    void deleteAll();
}
