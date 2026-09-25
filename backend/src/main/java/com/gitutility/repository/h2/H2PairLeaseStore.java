package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.PairLeaseRepository;
import com.gitutility.model.entity.PairLease;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the PairLeaseRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2PairLeaseStore implements PairLeaseRepository {

    private final PairLeaseJpaRepository jpa;

    @Override
    public int deleteOwned(String mappingId, String owner) {
        return jpa.deleteOwned(mappingId, owner);
    }

    @Override
    public int renewOwned(String mappingId, String owner, String jobId, Instant expiresAt, Instant updatedAt) {
        return jpa.renewOwned(mappingId, owner, jobId, expiresAt, updatedAt);
    }

    @Override
    public PairLease save(PairLease entity) {
        return jpa.save(entity);
    }

    @Override
    public List<PairLease> saveAll(Iterable<PairLease> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<PairLease> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<PairLease> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(PairLease entity) {
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
