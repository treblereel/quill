package org.treblereel.mcp.fixture;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@ApplicationScoped
@Alternative
@Priority(1)
public class MockPaymentService implements PaymentService {
    @Override
    public void processPayment(double amount) {}
}
