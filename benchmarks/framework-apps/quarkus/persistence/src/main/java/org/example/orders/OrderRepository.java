package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderRepository {
    public String save(String receipt) {
        return receipt;
    }
}
