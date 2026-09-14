package com.gitutility.repository;

import com.gitutility.model.entity.FeatureFlagsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeatureFlagsConfigRepository extends JpaRepository<FeatureFlagsConfig, Long> {
    Optional<FeatureFlagsConfig> findTopByOrderByIdAsc();
}
