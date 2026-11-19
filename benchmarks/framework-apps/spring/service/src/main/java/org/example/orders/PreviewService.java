package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class PreviewService {
    private final OrderService orders;
    @org.springframework.beans.factory.annotation.Autowired
    public PreviewService(OrderService orders) { this.orders = orders; }
    public String preview(long id) { return orders.place(id); }
}
