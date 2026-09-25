package com.gitutility.repository.h2;

import com.gitutility.model.entity.RefOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RefOriginJpaRepository extends JpaRepository<RefOrigin, String> {
    Optional<RefOrigin> findByMappingIdAndRefName(String mappingId, String refName);
}
