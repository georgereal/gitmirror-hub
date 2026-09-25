package com.gitutility.repository.h2;

import com.gitutility.model.entity.PairFailoverState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PairFailoverStateJpaRepository extends JpaRepository<PairFailoverState, String> {
    Optional<PairFailoverState> findByMappingId(String mappingId);
}
