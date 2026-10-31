package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class OrderRepository {
    public String save(String receipt) {
        return receipt;
    }
}
