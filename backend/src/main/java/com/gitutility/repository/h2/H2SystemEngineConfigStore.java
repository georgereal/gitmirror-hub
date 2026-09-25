package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.SystemEngineConfigRepository;
import com.gitutility.model.entity.SystemEngineConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the SystemEngineConfigRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2SystemEngineConfigStore implements SystemEngineConfigRepository {

    private final SystemEngineConfigJpaRepository jpa;

    @Override
    public Optional<SystemEngineConfig> findTopByOrderByIdAsc() {
        return jpa.findTopByOrderByIdAsc();
    }

    @Override
    public SystemEngineConfig save(SystemEngineConfig entity) {
        return jpa.save(entity);
    }

    @Override
    public List<SystemEngineConfig> saveAll(Iterable<SystemEngineConfig> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<SystemEngineConfig> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<SystemEngineConfig> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(SystemEngineConfig entity) {
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
