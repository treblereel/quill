package org.example.orders;

import org.springframework.stereotype.Component;

@Component
@org.springframework.beans.factory.annotation.Qualifier("card")
public class CardGateway implements PaymentGateway {
    public String charge(String id) { return "card:" + id; }
}
