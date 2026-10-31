package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class ReorderService {
    private final OrderService orders;
    @org.springframework.beans.factory.annotation.Autowired
    public ReorderService(OrderService orders) { this.orders = orders; }
    public String repeat(String id) { return orders.place(id); }
}
