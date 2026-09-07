package com.gitutility.repository;

import com.gitutility.model.entity.PrMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrMappingRepository extends JpaRepository<PrMapping, Long> {
    Optional<PrMapping> findByMappingIdAndSourcePrNumber(Long mappingId, Long sourcePrNumber);
    Optional<PrMapping> findByMappingIdAndTargetPrNumber(Long mappingId, Long targetPrNumber);
    Optional<PrMapping> findByMappingIdAndHeadBranchAndBaseBranch(Long mappingId, String headBranch, String baseBranch);
    List<PrMapping> findByMappingId(Long mappingId);
    List<PrMapping> findByMappingIdAndForkPrHeadTrue(Long mappingId);
}
