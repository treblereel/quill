package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class PaymentProcessor implements PaymentGateway {
    @Override
    public void charge(String orderId, double amount) {}
}
