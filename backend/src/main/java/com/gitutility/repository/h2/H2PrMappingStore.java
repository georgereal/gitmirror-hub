package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.model.entity.PrMapping;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the PrMappingRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2PrMappingStore implements PrMappingRepository {

    private final PrMappingJpaRepository jpa;

    @Override
    public Optional<PrMapping> findByMappingIdAndSourcePrNumber(String mappingId, Long sourcePrNumber) {
        return jpa.findByMappingIdAndSourcePrNumber(mappingId, sourcePrNumber);
    }

    @Override
    public Optional<PrMapping> findByMappingIdAndTargetPrNumber(String mappingId, Long targetPrNumber) {
        return jpa.findByMappingIdAndTargetPrNumber(mappingId, targetPrNumber);
    }

    @Override
    public Optional<PrMapping> findByMappingIdAndHeadBranchAndBaseBranch(String mappingId, String headBranch, String baseBranch) {
        return jpa.findByMappingIdAndHeadBranchAndBaseBranch(mappingId, headBranch, baseBranch);
    }

    @Override
    public List<PrMapping> findByMappingId(String mappingId) {
        return jpa.findByMappingId(mappingId);
    }

    @Override
    public List<PrMapping> findByMappingIdAndForkPrHeadTrue(String mappingId) {
        return jpa.findByMappingIdAndForkPrHeadTrue(mappingId);
    }

    @Override
    public PrMapping save(PrMapping entity) {
        return jpa.save(entity);
    }

    @Override
    public List<PrMapping> saveAll(Iterable<PrMapping> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<PrMapping> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<PrMapping> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(PrMapping entity) {
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
