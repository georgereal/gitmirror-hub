package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.model.entity.GitHubAppConfig;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the GitHubAppConfigRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2GitHubAppConfigStore implements GitHubAppConfigRepository {

    private final GitHubAppConfigJpaRepository jpa;

    @Override
    public Optional<GitHubAppConfig> findFirstByOrderByIdAsc() {
        return jpa.findFirstByOrderByIdAsc();
    }

    @Override
    public GitHubAppConfig save(GitHubAppConfig entity) {
        return jpa.save(entity);
    }

    @Override
    public List<GitHubAppConfig> saveAll(Iterable<GitHubAppConfig> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<GitHubAppConfig> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<GitHubAppConfig> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(GitHubAppConfig entity) {
        jpa.delete(entity);
    }

    @Override
    public void deleteById(String id) {
        jpa.deleteById(id);
    }

    @Override
    public void deleteAll() {
        jpa.deleteAll();
    }

}
