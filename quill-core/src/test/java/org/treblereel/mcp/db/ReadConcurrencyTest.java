package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReadConcurrencyTest {
    @Test
    void everyRequestCanHoldTwoReadersBesideTheWarmups() {
        assertEquals(10, ReadConcurrency.readersFor(4));
        assertEquals(66, ReadConcurrency.readersFor(32));
        assertTrue(ReadConcurrency.readersPerGeneration() >= 2 * ReadConcurrency.requests());
    }

    @Test
    void invalidConfigurationFallsBack() {
        assertEquals(4, ReadConcurrency.positive(null, 4));
        assertEquals(4, ReadConcurrency.positive(" ", 4));
        assertEquals(4, ReadConcurrency.positive("0", 4));
        assertEquals(4, ReadConcurrency.positive("x", 4));
        assertEquals(12, ReadConcurrency.positive(" 12 ", 4));
    }
}
