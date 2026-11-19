package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderService {
    private final OrderRepository repository;
    private final PaymentGateway gateway;
    private final OrderSettings settings;
    private final OrderEvents events;
    @jakarta.inject.Inject
    public OrderService(OrderRepository repository, @jakarta.inject.Named("card") PaymentGateway gateway,
        OrderSettings settings, OrderEvents events) {
        this.repository = repository;
        this.gateway = gateway;
        this.settings = settings;
        this.events = events;
    }
    public String place(String id) {
        String receipt = repository.save(settings.prefix() + ":" + gateway.charge(id));
        events.publish(new OrderPlaced(receipt));
        return receipt;
    }
    public String place(long id) { return "numeric:" + id; }
}
