package org.example.orders;

import org.springframework.stereotype.Component;

@Component
@org.springframework.beans.factory.annotation.Qualifier("cash")
public class CashGateway implements PaymentGateway {
    public String charge(String id) { return "cash:" + id; }
}
