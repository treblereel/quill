package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReorderService {
    private final OrderService orders;
    @jakarta.inject.Inject
    public ReorderService(OrderService orders) { this.orders = orders; }
    public String repeat(String id) { return orders.place(id); }
}
