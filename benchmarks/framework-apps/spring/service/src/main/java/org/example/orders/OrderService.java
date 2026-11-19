package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class OrderService {
    private final OrderRepository repository;
    private final PaymentGateway gateway;
    private final OrderSettings settings;
    private final OrderEvents events;
    @org.springframework.beans.factory.annotation.Autowired
    public OrderService(OrderRepository repository, @org.springframework.beans.factory.annotation.Qualifier("card") PaymentGateway gateway,
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
