package com.gitutility.repository.h2;

import com.gitutility.model.entity.InstanceHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InstanceHeartbeatJpaRepository extends JpaRepository<InstanceHeartbeat, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM InstanceHeartbeat h WHERE h.updatedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
