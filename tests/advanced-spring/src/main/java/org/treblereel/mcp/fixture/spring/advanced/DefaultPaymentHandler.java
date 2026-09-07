package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DefaultPaymentHandler {

    @Autowired
    private PaymentGateway paymentGateway;

    public void process(String orderId) {
        paymentGateway.charge(orderId, 0);
    }
}
