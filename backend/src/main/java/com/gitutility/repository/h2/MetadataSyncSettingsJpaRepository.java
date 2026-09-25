package com.gitutility.repository.h2;

import com.gitutility.model.entity.MetadataSyncSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MetadataSyncSettingsJpaRepository extends JpaRepository<MetadataSyncSettings, String> {
    Optional<MetadataSyncSettings> findTopByOrderByIdAsc();
}
