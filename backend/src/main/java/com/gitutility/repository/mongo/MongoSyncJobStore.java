package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the SyncJobRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoSyncJobStore implements SyncJobRepository {

    private final SyncJobMongoRepository repo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Override
    public List<SyncJob> findTop20ByOrderByCreatedAtDesc() {
return repo.findTop20ByOrderByCreatedAtDesc();
    }

    @Override
    public Page<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable) {
return repo.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Override
    public Page<SyncJob> findByMappingIdOrderByCreatedAtDesc(String mappingId, Pageable pageable) {
return repo.findByMappingIdOrderByCreatedAtDesc(mappingId, pageable);
    }

    @Override
    public Page<SyncJob> findByStatusOrderByCreatedAtDesc(SyncStatus status, Pageable pageable) {
return repo.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    @Override
    public List<SyncJob> findByStatus(SyncStatus status) {
return repo.findByStatus(status);
    }

    @Override
    public List<SyncJob> findByStatusAndMappingId(SyncStatus status, String mappingId) {
return repo.findByStatusAndMappingId(status, mappingId);
    }

    @Override
    public Optional<SyncJob> findByQueueMessageId(String queueMessageId) {
return repo.findByQueueMessageId(queueMessageId);
    }

    @Override
    public Page<SyncJob> search(SyncStatus status, String mappingId, TriggerType triggerType, String lane, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Criteria.where("status").is(status.name()));
        }
        if (mappingId != null) {
            filters.add(Criteria.where("mappingId").is(mappingId));
        }
        if (triggerType != null) {
            filters.add(Criteria.where("triggerType").is(triggerType.name()));
        }
        if (lane != null) {
            // Blank-ref test must match the JPA TRIM(ref) = '' semantics exactly.
            Criteria blankRef = Criteria.where("$expr").is(new org.bson.Document("$eq",
                    List.of(new org.bson.Document("$trim", new org.bson.Document("input",
                            new org.bson.Document("$ifNull", List.of("$ref", "")))), "")));
            if ("FULL".equals(lane)) {
                filters.add(new Criteria().orOperator(blankRef, Criteria.where("branch").is("*")));
            } else if ("INCREMENTAL".equals(lane)) {
                Criteria nonBlankRef = Criteria.where("$expr").is(new org.bson.Document("$ne",
                        List.of(new org.bson.Document("$trim", new org.bson.Document("input",
                                new org.bson.Document("$ifNull", List.of("$ref", "")))), "")));
                filters.add(nonBlankRef);
                filters.add(new Criteria().orOperator(
                        Criteria.where("branch").is(null), Criteria.where("branch").ne("*")));
            }
        }
        Query query = new Query();
        filters.forEach(query::addCriteria);
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        query.with(pageable);
        return PageableExecutionUtils.getPage(
                mongoTemplate.find(query, SyncJob.class),
                pageable,
                () -> mongoTemplate.count(Query.of(query).limit(-1).skip(-1), SyncJob.class));
    }

    @Override
    public long countByStatus(SyncStatus status) {
return repo.countByStatus(status);
    }

    @Override
    public long countJobsSince(Instant since) {
        return repo.countByCreatedAtGreaterThanEqual(since);
    }

    @Override
    public long countSuccessJobsSince(Instant since) {
        return repo.countByStatusAndCreatedAtGreaterThanEqual(SyncStatus.SUCCESS, since);
    }

    @Override
    public List<SyncJob> findForUsageWindow(Instant since) {
        return repo.findByStartedAtGreaterThanEqualOrStatusInOrderByStartedAtDesc(since,
                List.of(SyncStatus.IN_PROGRESS, SyncStatus.QUEUED, SyncStatus.PAUSED));
    }

    @Override
    public SyncJob save(SyncJob entity) {
return repo.save(entity);
    }

    @Override
    public List<SyncJob> saveAll(Iterable<SyncJob> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<SyncJob> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<SyncJob> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(SyncJob entity) {
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
