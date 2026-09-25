package com.gitutility.repository.h2;

import com.gitutility.model.entity.GitHubAppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GitHubAppConfigJpaRepository extends JpaRepository<GitHubAppConfig, String> {
    Optional<GitHubAppConfig> findFirstByOrderByIdAsc();
}
