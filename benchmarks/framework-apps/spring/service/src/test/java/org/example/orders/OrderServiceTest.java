package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderServiceTest {
    @Test void placesCardOrder() {
        OrderService orders = new OrderService(new OrderRepository(), new CardGateway(), new OrderSettings("test"), event -> {});
        assertEquals("test:card:A-1", orders.place("A-1"));
    }
}
