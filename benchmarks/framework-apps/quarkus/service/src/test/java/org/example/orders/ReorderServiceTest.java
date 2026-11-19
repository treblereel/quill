package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReorderServiceTest {
    @Test void repeatsCardOrder() {
        OrderService orders = new OrderService(new OrderRepository(), new CardGateway(), new OrderSettings("test"), event -> {});
        assertEquals("test:card:A-1", new ReorderService(orders).repeat("A-1"));
    }
}
