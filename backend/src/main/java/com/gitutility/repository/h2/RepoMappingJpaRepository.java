package com.gitutility.repository.h2;

import com.gitutility.model.entity.RepoMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface RepoMappingJpaRepository extends JpaRepository<RepoMapping, String> {

    Optional<RepoMapping> findByName(String name);

    @Query("SELECT m FROM RepoMapping m WHERE m.active = true AND " +
           "(m.repoAUrl = :url OR m.repoBUrl = :url OR " +
           "m.repoAUrl LIKE CONCAT('%', :repoPath, '%') OR m.repoBUrl LIKE CONCAT('%', :repoPath, '%'))")
    List<RepoMapping> findActiveMatchingRepo(@Param("url") String url, @Param("repoPath") String repoPath);

    long countBySourceCredentialIdOrTargetCredentialId(String sourceCredentialId, String targetCredentialId);

    List<RepoMapping> findByBulkSubmissionId(String bulkSubmissionId);
}

