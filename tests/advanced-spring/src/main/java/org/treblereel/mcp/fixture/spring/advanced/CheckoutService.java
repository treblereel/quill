package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService {

    private final PaymentGateway paymentGateway;

    public CheckoutService(@Qualifier("stripe") PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public void checkout(String orderId) {
        paymentGateway.charge(orderId, 99.99);
    }
}
