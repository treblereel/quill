package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FrameworkOrderEvents implements OrderEvents {
    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<OrderPlaced> events;
    public void publish(OrderPlaced event) { events.fire(event); }
}
