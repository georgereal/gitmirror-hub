package com.gitutility.repository.mongo;

import com.gitutility.model.entity.PairFailoverState;
import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.PairFailoverStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoPairFailoverStateStore implements PairFailoverStateRepository {

    private final PairFailoverStateMongoRepository repo;

    @Override
    public Optional<PairFailoverState> findByMappingId(String mappingId) {
        return repo.findByMappingId(mappingId);
    }

    @Override
    public List<PairFailoverState> findAll() {
        return repo.findAll();
    }

    @Override
    public PairFailoverState save(PairFailoverState entity) {
        return repo.save(entity);
    }

    @Override
    public void deleteById(String id) {
        repo.deleteById(id);
    }
}
