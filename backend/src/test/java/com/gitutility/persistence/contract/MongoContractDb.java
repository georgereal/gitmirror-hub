package com.gitutility.persistence.contract;

/**
 * Resolves the external MongoDB used by the mongo-side store contract tests.
 *
 * <p>No Docker / Testcontainers: the suite runs against a real MongoDB supplied
 * via the {@code MONGO_CONTRACT_URI} environment variable (or the
 * {@code -DMONGO_CONTRACT_URI=...} system property). When it is unset the mongo
 * contract suite is skipped, keeping plain {@code mvn test} green everywhere.
 *
 * <p>The resolved URI is published as JVM system properties ({@code MONGODB_URI}
 * and {@code spring.mongodb.uri} — the Boot 4.x key; {@code spring.data.mongodb.uri}
 * is deprecated since 4.0.0 and no longer bound) so every Spring test environment
 * picks it up regardless of initializer / context ordering. The target should be a
 * replica set (single node is fine) because the service methods under contract
 * are {@code @Transactional} and MongoDB transactions require replica-set
 * semantics.
 */
public final class MongoContractDb {

    private static final String URI = resolve();

    private static String resolve() {
        String fromSys = System.getProperty("MONGO_CONTRACT_URI");
        String fromEnv = System.getenv("MONGO_CONTRACT_URI");
        String uri = (fromSys != null && !fromSys.isBlank()) ? fromSys.trim()
                : (fromEnv != null && !fromEnv.isBlank()) ? fromEnv.trim() : null;
        if (uri != null) {
            System.setProperty("MONGODB_URI", uri);
            System.setProperty("spring.mongodb.uri", uri);
        }
        return uri;
    }

    private MongoContractDb() {
    }

    /** The configured contract MongoDB URI, or {@code null} when none is configured. */
    public static String uri() {
        return URI;
    }

    /** True when a contract MongoDB has been configured via MONGO_CONTRACT_URI. */
    public static boolean configured() {
        return URI != null;
    }
}
