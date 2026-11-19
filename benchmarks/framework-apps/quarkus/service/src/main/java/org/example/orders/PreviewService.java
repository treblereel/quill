package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PreviewService {
    private final OrderService orders;
    @jakarta.inject.Inject
    public PreviewService(OrderService orders) { this.orders = orders; }
    public String preview(long id) { return orders.place(id); }
}
