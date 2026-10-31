package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthServiceTest {
    @Test void reportsHealth() { assertEquals("healthy", new HealthService().status()); }
}
