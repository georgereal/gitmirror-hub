package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.RefOriginRepository;
import com.gitutility.model.entity.RefOrigin;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the RefOriginRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2RefOriginStore implements RefOriginRepository {

    private final RefOriginJpaRepository jpa;

    @Override
    public Optional<RefOrigin> findByMappingIdAndRefName(String mappingId, String refName) {
        return jpa.findByMappingIdAndRefName(mappingId, refName);
    }

    @Override
    public RefOrigin save(RefOrigin entity) {
        return jpa.save(entity);
    }

    @Override
    public List<RefOrigin> saveAll(Iterable<RefOrigin> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<RefOrigin> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<RefOrigin> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(RefOrigin entity) {
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
