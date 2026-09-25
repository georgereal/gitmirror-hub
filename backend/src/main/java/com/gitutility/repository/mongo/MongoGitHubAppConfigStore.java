package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.model.entity.GitHubAppConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the GitHubAppConfigRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoGitHubAppConfigStore implements GitHubAppConfigRepository {

    private final GitHubAppConfigMongoRepository repo;

    @Override
    public Optional<GitHubAppConfig> findFirstByOrderByIdAsc() {
return repo.findFirstByOrderByIdAsc();
    }

    @Override
    public GitHubAppConfig save(GitHubAppConfig entity) {
return repo.save(entity);
    }

    @Override
    public List<GitHubAppConfig> saveAll(Iterable<GitHubAppConfig> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<GitHubAppConfig> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<GitHubAppConfig> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(GitHubAppConfig entity) {
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
