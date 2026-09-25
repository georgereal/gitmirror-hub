package com.gitutility.repository.mongo;

import com.gitutility.model.entity.EchoLedgerEntry;
import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.EchoLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoEchoLedgerStore implements EchoLedgerRepository {

    private final EchoLedgerMongoRepository repo;

    @Override
    public void upsert(String repoKey, String token, Instant expiresAt) {
        String id = EchoLedgerEntry.idFor(repoKey, token);
        Optional<EchoLedgerEntry> existing = repo.findById(id);
        if (existing.isPresent()) {
            EchoLedgerEntry row = existing.get();
            row.setExpiresAt(expiresAt);
            repo.save(row);
            return;
        }
        try {
            repo.save(EchoLedgerEntry.builder()
                    .id(id)
                    .repoKey(repoKey)
                    .token(token)
                    .expiresAt(expiresAt)
                    .build());
        } catch (DuplicateKeyException race) {
            repo.findById(id).ifPresent(row -> {
                row.setExpiresAt(expiresAt);
                repo.save(row);
            });
        }
    }

    @Override
    public Optional<EchoLedgerEntry> findById(String id) {
        return repo.findById(id);
    }
}
