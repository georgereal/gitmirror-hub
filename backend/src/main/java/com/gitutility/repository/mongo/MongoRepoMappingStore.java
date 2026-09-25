package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.model.entity.RepoMapping;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the RepoMappingRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoRepoMappingStore implements RepoMappingRepository {

    private final RepoMappingMongoRepository repo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Override
    public Optional<RepoMapping> findByName(String name) {
return repo.findByName(name);
    }

    @Override
    public List<RepoMapping> findActiveMatchingRepo(String url, String repoPath) {
        String contains = ".*" + java.util.regex.Pattern.quote(repoPath == null ? "" : repoPath) + ".*";
        Query query = new Query(Criteria.where("active").is(true).andOperator(
                new Criteria().orOperator(
                        Criteria.where("repoAUrl").is(url),
                        Criteria.where("repoBUrl").is(url),
                        Criteria.where("repoAUrl").regex(contains),
                        Criteria.where("repoBUrl").regex(contains))));
        return mongoTemplate.find(query, RepoMapping.class);
    }

    @Override
    public long countBySourceCredentialIdOrTargetCredentialId(String sourceCredentialId, String targetCredentialId) {
return repo.countBySourceCredentialIdOrTargetCredentialId(sourceCredentialId, targetCredentialId);
    }

    @Override
    public List<RepoMapping> findByBulkSubmissionId(String bulkSubmissionId) {
return repo.findByBulkSubmissionId(bulkSubmissionId);
    }

    @Override
    public RepoMapping save(RepoMapping entity) {
return repo.save(entity);
    }

    @Override
    public List<RepoMapping> saveAll(Iterable<RepoMapping> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<RepoMapping> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<RepoMapping> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(RepoMapping entity) {
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
