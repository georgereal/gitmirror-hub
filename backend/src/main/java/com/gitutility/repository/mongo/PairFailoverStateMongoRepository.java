package com.gitutility.repository.mongo;

import com.gitutility.model.entity.PairFailoverState;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PairFailoverStateMongoRepository extends MongoRepository<PairFailoverState, String> {
    Optional<PairFailoverState> findByMappingId(String mappingId);
}
