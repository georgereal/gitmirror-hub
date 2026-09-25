package com.gitutility.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrMappingColumnLimitsTest {

    @Test
    void clampKeepsShortValuesUntouched() {
        assertNull(PrMapping.clampToColumnLimit(null));
        assertEquals("short title", PrMapping.clampToColumnLimit("short title"));
        String exactlyThousand = "x".repeat(1000);
        assertEquals(exactlyThousand, PrMapping.clampToColumnLimit(exactlyThousand));
    }

    @Test
    void clampTruncatesOversizedTitleToOneThousandChars() {
        String oversized = "y".repeat(1500);
        String clamped = PrMapping.clampToColumnLimit(oversized);
        assertEquals(1000, clamped.length());
        assertEquals("y".repeat(1000), clamped);
    }

    @Test
    void lifecycleClampTrimsTitleAndLastPushedTitleBeforeSave() {
        PrMapping pm = PrMapping.builder()
                .title("z".repeat(1500))
                .lastPushedTitle("w".repeat(1001))
                .build();

        pm.prepareForWrite();

        assertEquals(1000, pm.getTitle().length());
        assertEquals(1000, pm.getLastPushedTitle().length());
    }
}