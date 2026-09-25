package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.RefOriginRepository;
import com.gitutility.model.entity.RefOrigin;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the RefOriginRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoRefOriginStore implements RefOriginRepository {

    private final RefOriginMongoRepository repo;

    @Override
    public Optional<RefOrigin> findByMappingIdAndRefName(String mappingId, String refName) {
return repo.findByMappingIdAndRefName(mappingId, refName);
    }

    @Override
    public RefOrigin save(RefOrigin entity) {
return repo.save(entity);
    }

    @Override
    public List<RefOrigin> saveAll(Iterable<RefOrigin> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<RefOrigin> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<RefOrigin> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(RefOrigin entity) {
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
