package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Qualifier("stripe")
public class StripeGateway implements PaymentGateway {
    @Override
    public void charge(String orderId, double amount) {}
}
