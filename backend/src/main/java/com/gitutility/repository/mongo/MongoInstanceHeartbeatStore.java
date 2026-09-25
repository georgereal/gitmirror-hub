package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.InstanceHeartbeatRepository;
import com.gitutility.model.entity.InstanceHeartbeat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the InstanceHeartbeatRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoInstanceHeartbeatStore implements InstanceHeartbeatRepository {

    private final InstanceHeartbeatMongoRepository repo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return (int) mongoTemplate.remove(
                Query.query(Criteria.where("updatedAt").lt(cutoff)), InstanceHeartbeat.class).getDeletedCount();
    }

    @Override
    public InstanceHeartbeat save(InstanceHeartbeat entity) {
return repo.save(entity);
    }

    @Override
    public List<InstanceHeartbeat> saveAll(Iterable<InstanceHeartbeat> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<InstanceHeartbeat> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<InstanceHeartbeat> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(InstanceHeartbeat entity) {
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
