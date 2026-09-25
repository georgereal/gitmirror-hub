package com.gitutility.repository.mongo;

import com.gitutility.model.entity.PrMapping;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation backing the PrMappingRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface PrMappingMongoRepository extends MongoRepository<PrMapping, String> {

    Optional<PrMapping> findByMappingIdAndSourcePrNumber(String mappingId, Long sourcePrNumber);

    Optional<PrMapping> findByMappingIdAndTargetPrNumber(String mappingId, Long targetPrNumber);

    Optional<PrMapping> findByMappingIdAndHeadBranchAndBaseBranch(String mappingId, String headBranch, String baseBranch);

    List<PrMapping> findByMappingId(String mappingId);

    List<PrMapping> findByMappingIdAndForkPrHeadTrue(String mappingId);
}
