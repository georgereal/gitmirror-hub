package com.gitutility.repository;

import com.gitutility.model.entity.RepoMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepoMappingRepository extends JpaRepository<RepoMapping, Long> {

    Optional<RepoMapping> findByName(String name);

    @Query("SELECT m FROM RepoMapping m WHERE m.active = true AND " +
           "(m.repoAUrl = :url OR m.repoBUrl = :url OR " +
           "m.repoAUrl LIKE CONCAT('%', :repoPath, '%') OR m.repoBUrl LIKE CONCAT('%', :repoPath, '%'))")
    List<RepoMapping> findActiveMatchingRepo(@Param("url") String url, @Param("repoPath") String repoPath);

    long countBySourceCredentialIdOrTargetCredentialId(Long sourceCredentialId, Long targetCredentialId);
}
