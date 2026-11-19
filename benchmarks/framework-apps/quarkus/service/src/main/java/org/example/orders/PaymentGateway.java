package org.example.orders;

public interface PaymentGateway {
    String charge(String id);
}
