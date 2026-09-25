package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.FeatureFlagsConfigRepository;
import com.gitutility.model.entity.FeatureFlagsConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the FeatureFlagsConfigRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoFeatureFlagsConfigStore implements FeatureFlagsConfigRepository {

    private final FeatureFlagsConfigMongoRepository repo;

    @Override
    public Optional<FeatureFlagsConfig> findTopByOrderByIdAsc() {
return repo.findTopByOrderByIdAsc();
    }

    @Override
    public FeatureFlagsConfig save(FeatureFlagsConfig entity) {
return repo.save(entity);
    }

    @Override
    public List<FeatureFlagsConfig> saveAll(Iterable<FeatureFlagsConfig> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<FeatureFlagsConfig> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<FeatureFlagsConfig> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(FeatureFlagsConfig entity) {
repo.delete(entity);
    }

    @Override
    public void deleteById(String id) {
repo.deleteById(id);
    }

    @Override
    public void deleteAll() {
repo.deleteAll();
    }

}
