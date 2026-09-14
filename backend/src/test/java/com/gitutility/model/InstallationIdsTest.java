package com.gitutility.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstallationIdsTest {

    @Test
    void encodeDecodeRoundTrip() {
        String json = InstallationIds.encode(List.of("111", "222", "111"));
        assertEquals(List.of("111", "222"), InstallationIds.decode(json, null));
        assertEquals("111", InstallationIds.primary(List.of("111", "222")));
    }

    @Test
    void fallsBackToPrimaryWhenJsonEmpty() {
        assertEquals(List.of("99"), InstallationIds.decode(null, "99"));
        assertEquals(List.of("99"), InstallationIds.decode("[]", "99"));
        assertTrue(InstallationIds.decode(null, null).isEmpty());
    }

    @Test
    void containsMatchesTrimmed() {
        assertTrue(InstallationIds.contains(List.of("1", "2"), " 2 "));
        assertFalse(InstallationIds.contains(List.of("1", "2"), "3"));
    }
}
