package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.InstanceHeartbeatRepository;
import com.gitutility.model.entity.InstanceHeartbeat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the InstanceHeartbeatRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2InstanceHeartbeatStore implements InstanceHeartbeatRepository {

    private final InstanceHeartbeatJpaRepository jpa;

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jpa.deleteOlderThan(cutoff);
    }

    @Override
    public InstanceHeartbeat save(InstanceHeartbeat entity) {
        return jpa.save(entity);
    }

    @Override
    public List<InstanceHeartbeat> saveAll(Iterable<InstanceHeartbeat> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<InstanceHeartbeat> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<InstanceHeartbeat> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(InstanceHeartbeat entity) {
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
