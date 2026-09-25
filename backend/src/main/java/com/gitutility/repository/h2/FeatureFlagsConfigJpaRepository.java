package com.gitutility.repository.h2;

import com.gitutility.model.entity.FeatureFlagsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeatureFlagsConfigJpaRepository extends JpaRepository<FeatureFlagsConfig, String> {
    Optional<FeatureFlagsConfig> findTopByOrderByIdAsc();
}
