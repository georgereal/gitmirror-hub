package com.gitutility.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceProviderTest {

    @Test
    void defaultsToH2WhenBlank() {
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from(null));
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from(""));
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from("   "));
    }

    @Test
    void parsesCanonicalAndAliases() {
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from("h2"));
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from("H2"));
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from("h_2"));
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from("h2db"));
        assertEquals(PersistenceProvider.H2, PersistenceProvider.from("jpa"));
        assertEquals(PersistenceProvider.MONGO, PersistenceProvider.from("mongo"));
        assertEquals(PersistenceProvider.MONGO, PersistenceProvider.from("MongoDB"));
        assertEquals(PersistenceProvider.MONGO, PersistenceProvider.from("mongo-db"));
        assertEquals(PersistenceProvider.MONGO, PersistenceProvider.from("  mongo  "));
    }

    @Test
    void wireIdsAreCanonical() {
        assertEquals("h2", PersistenceProvider.H2.wireId());
        assertEquals("mongo", PersistenceProvider.MONGO.wireId());
    }

    @Test
    void mongoFlag() {
        assertFalse(PersistenceProvider.H2.isMongo());
        assertTrue(PersistenceProvider.MONGO.isMongo());
    }

    @Test
    void rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> PersistenceProvider.from("postgres"));
        assertThrows(IllegalArgumentException.class, () -> PersistenceProvider.from("none"));
    }
}
