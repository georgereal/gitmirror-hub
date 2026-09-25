package com.gitutility.repository.h2;

import com.gitutility.model.entity.MetadataSyncSettings;
import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.MetadataSyncSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnH2
@RequiredArgsConstructor
public class H2MetadataSyncSettingsStore implements MetadataSyncSettingsRepository {

    private final MetadataSyncSettingsJpaRepository jpa;

    @Override
    public Optional<MetadataSyncSettings> findTopByOrderByIdAsc() {
        return jpa.findTopByOrderByIdAsc();
    }

    @Override
    public MetadataSyncSettings save(MetadataSyncSettings entity) {
        return jpa.save(entity);
    }

    @Override
    public List<MetadataSyncSettings> saveAll(Iterable<MetadataSyncSettings> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<MetadataSyncSettings> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<MetadataSyncSettings> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(MetadataSyncSettings entity) {
        jpa.delete(entity);
    }

    @Override
    public void deleteById(String id) {
        jpa.deleteById(id);
    }

    @Override
    public void deleteAll() {
        jpa.deleteAll();
    }
}
