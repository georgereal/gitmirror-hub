package com.gitutility.repository.h2;

import com.gitutility.model.entity.PairFailoverState;
import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.PairFailoverStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnH2
@RequiredArgsConstructor
public class H2PairFailoverStateStore implements PairFailoverStateRepository {

    private final PairFailoverStateJpaRepository jpa;

    @Override
    public Optional<PairFailoverState> findByMappingId(String mappingId) {
        return jpa.findByMappingId(mappingId);
    }

    @Override
    public List<PairFailoverState> findAll() {
        return jpa.findAll();
    }

    @Override
    public PairFailoverState save(PairFailoverState entity) {
        return jpa.save(entity);
    }

    @Override
    public void deleteById(String id) {
        jpa.deleteById(id);
    }
}
