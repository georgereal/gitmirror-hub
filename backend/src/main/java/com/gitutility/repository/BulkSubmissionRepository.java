package com.gitutility.repository;

import com.gitutility.model.entity.BulkSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BulkSubmissionRepository extends JpaRepository<BulkSubmission, Long> {
}
