package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewServiceTest {
    @Test void previewsNumericOrder() {
        OrderService orders = new OrderService(new OrderRepository(), new CardGateway(), new OrderSettings("test"), event -> {});
        assertEquals("numeric:7", new PreviewService(orders).preview(7));
    }
}
