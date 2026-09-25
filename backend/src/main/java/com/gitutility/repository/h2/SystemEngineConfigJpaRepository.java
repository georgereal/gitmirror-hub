package com.gitutility.repository.h2;

import com.gitutility.model.entity.SystemEngineConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SystemEngineConfigJpaRepository extends JpaRepository<SystemEngineConfig, String> {
    Optional<SystemEngineConfig> findTopByOrderByIdAsc();
}
