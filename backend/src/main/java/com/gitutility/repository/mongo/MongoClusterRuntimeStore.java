package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.ClusterRuntimeRepository;
import com.gitutility.model.entity.ClusterRuntime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the ClusterRuntimeRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoClusterRuntimeStore implements ClusterRuntimeRepository {

    private final ClusterRuntimeMongoRepository repo;

    @Override
    public ClusterRuntime save(ClusterRuntime entity) {
return repo.save(entity);
    }

    @Override
    public List<ClusterRuntime> saveAll(Iterable<ClusterRuntime> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<ClusterRuntime> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<ClusterRuntime> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(ClusterRuntime entity) {
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
