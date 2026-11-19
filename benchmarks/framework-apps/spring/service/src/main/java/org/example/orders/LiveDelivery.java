package org.example.orders;

import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("!test")
public class LiveDelivery implements DeliveryChannel {
    public String name() { return "live"; }
}
