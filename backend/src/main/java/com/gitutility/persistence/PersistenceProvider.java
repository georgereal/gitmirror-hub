package com.gitutility.persistence;

/**
 * Pluggable persistence store. Canonical wire values (env / YAML):
 * {@code h2} (default), {@code mongo}.
 *
 * <p>Exactly one store is active per process — the {@link PersistenceEnvironmentPostProcessor}
 * excludes the inactive stack's autoconfiguration, so there is no fallback and no dual wiring.</p>
 */
public enum PersistenceProvider {
    /** File-based H2 behind Spring Data JPA (default; local dev / local multi-pod smoke). */
    H2,
    /** MongoDB via Spring Data MongoDB (enterprise scale-out; must be reachable at startup). */
    MONGO;

    /** Canonical lowercase token for env, YAML, and API responses. */
    public String wireId() {
        return switch (this) {
            case H2 -> "h2";
            case MONGO -> "mongo";
        };
    }

    public static PersistenceProvider from(String raw) {
        if (raw == null || raw.isBlank()) {
            return H2;
        }
        String key = raw.trim().toLowerCase().replace('_', '-');
        return switch (key) {
            case "h2", "h-2", "h2db", "jpa", "sql" -> H2;
            case "mongo", "mongodb", "mongo-db" -> MONGO;
            default -> throw new IllegalArgumentException(
                    "Unknown git-utility.persistence.provider '" + raw
                            + "' (supported: h2, mongo)");
        };
    }

    public boolean isMongo() {
        return this == MONGO;
    }
}
