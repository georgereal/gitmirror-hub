package com.gitutility.repository;

import com.gitutility.model.entity.GitHubAppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitHubAppConfigRepository extends JpaRepository<GitHubAppConfig, Long> {
    Optional<GitHubAppConfig> findFirstByOrderByIdAsc();
}
