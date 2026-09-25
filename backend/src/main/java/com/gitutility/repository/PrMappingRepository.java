package com.gitutility.repository;

import com.gitutility.model.entity.PrMapping;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for mirrored pull-request mappings.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface PrMappingRepository {

    Optional<PrMapping> findByMappingIdAndSourcePrNumber(String mappingId, Long sourcePrNumber);

    Optional<PrMapping> findByMappingIdAndTargetPrNumber(String mappingId, Long targetPrNumber);

    Optional<PrMapping> findByMappingIdAndHeadBranchAndBaseBranch(String mappingId, String headBranch, String baseBranch);

    List<PrMapping> findByMappingId(String mappingId);

    List<PrMapping> findByMappingIdAndForkPrHeadTrue(String mappingId);

    PrMapping save(PrMapping entity);

    List<PrMapping> saveAll(Iterable<PrMapping> entities);

    Optional<PrMapping> findById(String id);

    boolean existsById(String id);

    List<PrMapping> findAll();

    long count();

    void delete(PrMapping entity);

    void deleteById(String id);

    void deleteAll();
}
