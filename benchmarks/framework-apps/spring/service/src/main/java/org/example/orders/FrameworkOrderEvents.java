package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class FrameworkOrderEvents implements OrderEvents {
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationEventPublisher events;
    public void publish(OrderPlaced event) { events.publishEvent(event); }
}
