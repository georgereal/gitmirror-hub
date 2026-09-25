package com.gitutility.repository.h2;

import com.gitutility.model.entity.BulkSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BulkSubmissionJpaRepository extends JpaRepository<BulkSubmission, String> {
}
