package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.FeatureFlagsConfigRepository;
import com.gitutility.model.entity.FeatureFlagsConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the FeatureFlagsConfigRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2FeatureFlagsConfigStore implements FeatureFlagsConfigRepository {

    private final FeatureFlagsConfigJpaRepository jpa;

    @Override
    public Optional<FeatureFlagsConfig> findTopByOrderByIdAsc() {
        return jpa.findTopByOrderByIdAsc();
    }

    @Override
    public FeatureFlagsConfig save(FeatureFlagsConfig entity) {
        return jpa.save(entity);
    }

    @Override
    public List<FeatureFlagsConfig> saveAll(Iterable<FeatureFlagsConfig> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<FeatureFlagsConfig> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<FeatureFlagsConfig> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(FeatureFlagsConfig entity) {
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
