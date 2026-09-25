package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.SystemEngineConfigRepository;
import com.gitutility.model.entity.SystemEngineConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the SystemEngineConfigRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoSystemEngineConfigStore implements SystemEngineConfigRepository {

    private final SystemEngineConfigMongoRepository repo;

    @Override
    public Optional<SystemEngineConfig> findTopByOrderByIdAsc() {
return repo.findTopByOrderByIdAsc();
    }

    @Override
    public SystemEngineConfig save(SystemEngineConfig entity) {
return repo.save(entity);
    }

    @Override
    public List<SystemEngineConfig> saveAll(Iterable<SystemEngineConfig> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<SystemEngineConfig> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<SystemEngineConfig> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(SystemEngineConfig entity) {
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
