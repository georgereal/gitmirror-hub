package com.gitutility.repository;

import com.gitutility.model.entity.PairFailoverState;

import java.util.List;
import java.util.Optional;

public interface PairFailoverStateRepository {

    Optional<PairFailoverState> findByMappingId(String mappingId);

    List<PairFailoverState> findAll();

    PairFailoverState save(PairFailoverState entity);

    void deleteById(String id);
}
