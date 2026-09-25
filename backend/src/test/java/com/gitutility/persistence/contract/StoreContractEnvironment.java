package com.gitutility.persistence.contract;

/**
 * Gate for the mongo-side store contract suite: enabled only when a real
 * MongoDB is supplied via the {@code MONGO_CONTRACT_URI} environment variable
 * (or the {@code -DMONGO_CONTRACT_URI=...} system property). No Docker or
 * Testcontainers involved — when the variable is unset the mongo contract
 * suite is skipped, keeping plain {@code mvn test} green everywhere.
 */
public final class StoreContractEnvironment {

    private StoreContractEnvironment() {
    }

    /** JUnit 5 condition method referenced by {@code @EnabledIf}. */
    public static boolean mongoContractAvailable() {
        return MongoContractDb.configured();
    }
}
