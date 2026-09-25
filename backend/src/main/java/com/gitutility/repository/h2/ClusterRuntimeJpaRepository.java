package com.gitutility.repository.h2;

import com.gitutility.model.entity.ClusterRuntime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClusterRuntimeJpaRepository extends JpaRepository<ClusterRuntime, String> {
}
