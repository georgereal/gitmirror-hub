package com.gitutility.persistence.store;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.time.Duration;

/**
 * MongoDB counterpart of the H2 {@code DatabaseSchemaMigrator}: pings the selected
 * database (fail-fast — no fallback) and creates/verifies the unique, compound and
 * TTL indexes the facades rely on. Active only when
 * {@code git-utility.persistence.provider=mongo}.
 */
@Component
@OnMongo
@RequiredArgsConstructor
@Slf4j
public class MongoSchemaInitializer {

    /** Retention for discarded webhook records — 7 days, matching the H2 cleanup job semantics. */
    private static final long UNMAPPED_RETENTION_SECONDS = 7L * 24 * 3600;

    private final MongoTemplate mongoTemplate;

    @PostConstruct
    public void initialize() {
        // Fail fast when the configured MongoDB is unreachable — no fallback to another store.
        mongoTemplate.executeCommand(new Document("ping", 1));
        log.info("MongoDB ping OK — creating/verifying indexes...");

        ensureUnique("repo_mappings", "name");
        // Sparse: most sync jobs carry no queueMessageId, and MongoDB treats every null as a
        // duplicate key — a plain unique index would reject the second null (E11000). Sparse skips
        // documents missing the field while keeping non-null values unique.
        ensureUniqueSparse("sync_jobs", "queueMessageId");
        ensureUniqueCompound("ref_origins", "mappingId", "refName");

        ensureCompound("pr_mappings", "mappingId", "sourcePrNumber");
        ensureCompound("sync_jobs", "status", "createdAt");
        ensureCompound("sync_jobs", "mappingId", "createdAt");
        ensureCompound("sync_conflicts", "mappingId", "status");
        ensureTtl("echo_ledger", "expiresAt", 0);

        ensureIndex("sync_audit_logs", "jobId");
        ensureIndex("unmapped_webhook_events", "repoFullName");

        // 7-day retention for discarded webhook records (matches the H2 cleanup job semantics).
        // A TTL index also serves plain ascending receivedAt queries, so no separate non-TTL index
        // may exist on the same field: MongoDB rejects a same-named index with different options
        // (IndexOptionsConflict, code 85), which would abort startup on an already-initialized store.
        ensureTtl("unmapped_webhook_events", "receivedAt", UNMAPPED_RETENTION_SECONDS);

        log.info("MongoDB persistence initialization complete.");
    }

    private void ensureUnique(String collection, String field) {
        reconcile(collection, field + "_1",
                info -> info.isUnique() && !info.isSparse(),
                new Index().on(field, Sort.Direction.ASC).unique());
        log.info("MongoDB index verified: {}.{} (unique ascending)", collection, field);
    }

    private void ensureUniqueSparse(String collection, String field) {
        reconcile(collection, field + "_1",
                info -> info.isUnique() && info.isSparse(),
                new Index().on(field, Sort.Direction.ASC).unique().sparse());
        log.info("MongoDB index verified: {}.{} (unique sparse ascending)", collection, field);
    }

    private void ensureUniqueCompound(String collection, String fieldA, String fieldB) {
        reconcile(collection, fieldA + "_1_" + fieldB + "_1",
                info -> info.isUnique() && !info.isSparse(),
                new Index().on(fieldA, Sort.Direction.ASC).on(fieldB, Sort.Direction.ASC).unique());
        log.info("MongoDB index verified: {}.{}+{} (unique compound ascending)", collection, fieldA, fieldB);
    }

    private void ensureCompound(String collection, String fieldA, String fieldB) {
        reconcile(collection, fieldA + "_1_" + fieldB + "_-1",
                info -> !info.isUnique(),
                new Index().on(fieldA, Sort.Direction.ASC).on(fieldB, Sort.Direction.DESC));
        log.info("MongoDB index verified: {}.{}+{}", collection, fieldA, fieldB);
    }

    private void ensureIndex(String collection, String field) {
        reconcile(collection, field + "_1",
                info -> !info.isUnique() && info.getExpireAfter().isEmpty(),
                new Index().on(field, Sort.Direction.ASC));
        log.info("MongoDB index verified: {}.{} (ascending)", collection, field);
    }

    private void ensureTtl(String collection, String field, long expireAfterSeconds) {
        Duration expected = Duration.ofSeconds(expireAfterSeconds);
        reconcile(collection, field + "_1",
                info -> expected.equals(info.getExpireAfter().orElse(null)),
                new Index().on(field, Sort.Direction.ASC).expire(expected));
        log.info("MongoDB index verified: {}.{} (ascending, TTL {}s)", collection, field, expireAfterSeconds);
    }

    /**
     * Ensures {@code definition} exists under {@code indexName}, first dropping any pre-existing index
     * of that name whose options no longer satisfy {@code optionsMatch}. MongoDB refuses to alter an
     * index in place — re-creating it with different options fails with {@code IndexOptionsConflict}
     * (code 85) — so an option change (unique → sparse, plain → TTL, …) must drop-then-recreate. This
     * keeps repeated boots idempotent and transparently migrates stores initialized by an older build
     * (e.g. a non-sparse {@code queueMessageId} unique index left by a pre-fix deployment).
     */
    private void reconcile(String collection, String indexName,
                           java.util.function.Predicate<IndexInfo> optionsMatch, Index definition) {
        IndexOperations indexOps = mongoTemplate.indexOps(collection);
        indexOps.getIndexInfo().stream()
                .filter(info -> indexName.equals(info.getName()))
                .findFirst()
                .filter(info -> !optionsMatch.test(info))
                .ifPresent(info -> {
                    log.warn("MongoDB index {}.{} exists with different options ({}) — dropping and recreating",
                            collection, indexName, info);
                    indexOps.dropIndex(indexName);
                });
        indexOps.createIndex(definition);
    }
}
