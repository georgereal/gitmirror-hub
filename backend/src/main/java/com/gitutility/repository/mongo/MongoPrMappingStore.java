package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.model.entity.PrMapping;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the PrMappingRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoPrMappingStore implements PrMappingRepository {

    private final PrMappingMongoRepository repo;

    @Override
    public Optional<PrMapping> findByMappingIdAndSourcePrNumber(String mappingId, Long sourcePrNumber) {
return repo.findByMappingIdAndSourcePrNumber(mappingId, sourcePrNumber);
    }

    @Override
    public Optional<PrMapping> findByMappingIdAndTargetPrNumber(String mappingId, Long targetPrNumber) {
return repo.findByMappingIdAndTargetPrNumber(mappingId, targetPrNumber);
    }

    @Override
    public Optional<PrMapping> findByMappingIdAndHeadBranchAndBaseBranch(String mappingId, String headBranch, String baseBranch) {
return repo.findByMappingIdAndHeadBranchAndBaseBranch(mappingId, headBranch, baseBranch);
    }

    @Override
    public List<PrMapping> findByMappingId(String mappingId) {
return repo.findByMappingId(mappingId);
    }

    @Override
    public List<PrMapping> findByMappingIdAndForkPrHeadTrue(String mappingId) {
return repo.findByMappingIdAndForkPrHeadTrue(mappingId);
    }

    @Override
    public PrMapping save(PrMapping entity) {
return repo.save(entity);
    }

    @Override
    public List<PrMapping> saveAll(Iterable<PrMapping> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<PrMapping> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<PrMapping> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(PrMapping entity) {
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
