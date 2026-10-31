package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@jakarta.inject.Named("cash")
public class CashGateway implements PaymentGateway {
    public String charge(String id) { return "cash:" + id; }
}
