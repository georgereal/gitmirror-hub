package com.gitutility.repository.h2;

import com.gitutility.model.entity.EchoLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EchoLedgerJpaRepository extends JpaRepository<EchoLedgerEntry, String> {
}
