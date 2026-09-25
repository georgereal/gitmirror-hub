package com.gitutility.repository.h2;

import com.gitutility.model.entity.PairLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface PairLeaseJpaRepository extends JpaRepository<PairLease, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM PairLease p
            WHERE p.mappingId = :mappingId
              AND p.ownerInstance = :owner
            """)
    int deleteOwned(@Param("mappingId") String mappingId, @Param("owner") String owner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PairLease p
            SET p.expiresAt = :expiresAt, p.updatedAt = :updatedAt, p.jobId = :jobId
            WHERE p.mappingId = :mappingId
              AND p.ownerInstance = :owner
            """)
    int renewOwned(@Param("mappingId") String mappingId,
                   @Param("owner") String owner,
                   @Param("jobId") String jobId,
                   @Param("expiresAt") Instant expiresAt,
                   @Param("updatedAt") Instant updatedAt);
}
