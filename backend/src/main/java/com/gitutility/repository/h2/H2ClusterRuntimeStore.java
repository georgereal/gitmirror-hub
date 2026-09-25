package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.ClusterRuntimeRepository;
import com.gitutility.model.entity.ClusterRuntime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the ClusterRuntimeRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2ClusterRuntimeStore implements ClusterRuntimeRepository {

    private final ClusterRuntimeJpaRepository jpa;

    @Override
    public ClusterRuntime save(ClusterRuntime entity) {
        return jpa.save(entity);
    }

    @Override
    public List<ClusterRuntime> saveAll(Iterable<ClusterRuntime> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<ClusterRuntime> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<ClusterRuntime> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(ClusterRuntime entity) {
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
