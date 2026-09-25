package com.gitutility.repository;

import com.gitutility.model.entity.GitHubAppConfig;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for the singleton SCM provider configuration row.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface GitHubAppConfigRepository {

    Optional<GitHubAppConfig> findFirstByOrderByIdAsc();

    GitHubAppConfig save(GitHubAppConfig entity);

    List<GitHubAppConfig> saveAll(Iterable<GitHubAppConfig> entities);

    Optional<GitHubAppConfig> findById(String id);

    boolean existsById(String id);

    List<GitHubAppConfig> findAll();

    long count();

    void delete(GitHubAppConfig entity);

    void deleteById(String id);

    void deleteAll();
}
