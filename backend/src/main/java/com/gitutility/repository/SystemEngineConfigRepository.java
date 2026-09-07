package com.gitutility.repository;

import com.gitutility.model.entity.SystemEngineConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemEngineConfigRepository extends JpaRepository<SystemEngineConfig, Long> {
    Optional<SystemEngineConfig> findTopByOrderByIdAsc();
}
