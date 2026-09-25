package com.gitutility.repository.h2;

import com.gitutility.model.entity.PrMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PrMappingJpaRepository extends JpaRepository<PrMapping, String> {
    Optional<PrMapping> findByMappingIdAndSourcePrNumber(String mappingId, Long sourcePrNumber);
    Optional<PrMapping> findByMappingIdAndTargetPrNumber(String mappingId, Long targetPrNumber);
    Optional<PrMapping> findByMappingIdAndHeadBranchAndBaseBranch(String mappingId, String headBranch, String baseBranch);
    List<PrMapping> findByMappingId(String mappingId);
    List<PrMapping> findByMappingIdAndForkPrHeadTrue(String mappingId);
}
