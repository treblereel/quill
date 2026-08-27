package org.treblereel.mcp.fixture;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class StripePaymentService implements PaymentService {
    @Override
    public void processPayment(double amount) {}
}
