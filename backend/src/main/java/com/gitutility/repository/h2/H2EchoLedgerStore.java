package com.gitutility.repository.h2;

import com.gitutility.model.entity.EchoLedgerEntry;
import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.EchoLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@OnH2
@RequiredArgsConstructor
public class H2EchoLedgerStore implements EchoLedgerRepository {

    private final EchoLedgerJpaRepository jpa;

    @Override
    public void upsert(String repoKey, String token, Instant expiresAt) {
        String id = EchoLedgerEntry.idFor(repoKey, token);
        Optional<EchoLedgerEntry> existing = jpa.findById(id);
        if (existing.isPresent()) {
            EchoLedgerEntry row = existing.get();
            row.setExpiresAt(expiresAt);
            jpa.save(row);
            return;
        }
        try {
            jpa.save(EchoLedgerEntry.builder()
                    .id(id)
                    .repoKey(repoKey)
                    .token(token)
                    .expiresAt(expiresAt)
                    .build());
        } catch (DataIntegrityViolationException race) {
            jpa.findById(id).ifPresent(row -> {
                row.setExpiresAt(expiresAt);
                jpa.save(row);
            });
        }
    }

    @Override
    public Optional<EchoLedgerEntry> findById(String id) {
        return jpa.findById(id);
    }
}
