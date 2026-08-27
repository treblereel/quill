package org.treblereel.mcp.fixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OrderService {

    @Inject
    PaymentService paymentService;

    public OrderDTO createOrder(String item) {
        paymentService.processPayment(9.99);
        return new OrderDTO(item);
    }
}
