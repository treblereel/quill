package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@io.quarkus.arc.profile.UnlessBuildProfile("test")
public class LiveDelivery implements DeliveryChannel {
    public String name() { return "live"; }
}
