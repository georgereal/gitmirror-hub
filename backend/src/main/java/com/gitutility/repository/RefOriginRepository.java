package com.gitutility.repository;

import com.gitutility.model.entity.RefOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefOriginRepository extends JpaRepository<RefOrigin, Long> {
    Optional<RefOrigin> findByMappingIdAndRefName(Long mappingId, String refName);
}
