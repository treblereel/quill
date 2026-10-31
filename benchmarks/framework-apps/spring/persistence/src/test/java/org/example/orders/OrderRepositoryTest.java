package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderRepositoryTest {
    @Test void preservesReceipt() { assertEquals("receipt", new OrderRepository().save("receipt")); }
}
