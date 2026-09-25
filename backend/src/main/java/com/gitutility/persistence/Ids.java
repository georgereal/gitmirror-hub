package com.gitutility.persistence;

import org.bson.types.ObjectId;

/**
 * Shared id generator for both persistence providers: 24-char lowercase hex of a fresh
 * Mongo ObjectId. The JPA (H2) store and the MongoDB store use the same helper so the id
 * shape is identical everywhere (REST paths, AMQP payloads, WebSocket events, operator UI).
 */
public final class Ids {

    private Ids() {
    }

    /** New 24-char ObjectId-hex id (lexicographic sort ≈ chronological creation). */
    public static String newId() {
        return new ObjectId().toHexString();
    }

    /** True when {@code id} is null or blank (unsaved / generated on save). */
    public static boolean isUnset(String id) {
        return id == null || id.isBlank();
    }
}
