package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.PairLeaseRepository;
import com.gitutility.model.entity.PairLease;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the PairLeaseRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoPairLeaseStore implements PairLeaseRepository {

    private final PairLeaseMongoRepository repo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Override
    public int deleteOwned(String mappingId, String owner) {
        return (int) mongoTemplate.remove(
                Query.query(Criteria.where("mappingId").is(mappingId).and("ownerInstance").is(owner)),
                PairLease.class).getDeletedCount();
    }

    @Override
    public int renewOwned(String mappingId, String owner, String jobId, Instant expiresAt, Instant updatedAt) {
        com.mongodb.client.result.UpdateResult result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("mappingId").is(mappingId).and("ownerInstance").is(owner)),
                new Update()
                        .set("expiresAt", expiresAt)
                        .set("updatedAt", updatedAt)
                        .set("jobId", jobId),
                PairLease.class);
        return (int) result.getModifiedCount();
    }

    @Override
    public PairLease save(PairLease entity) {
return repo.save(entity);
    }

    @Override
    public List<PairLease> saveAll(Iterable<PairLease> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<PairLease> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<PairLease> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(PairLease entity) {
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
