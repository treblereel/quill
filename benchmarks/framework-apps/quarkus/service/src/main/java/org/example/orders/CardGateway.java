package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@jakarta.inject.Named("card")
public class CardGateway implements PaymentGateway {
    public String charge(String id) { return "card:" + id; }
}
