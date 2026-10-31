package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@io.quarkus.arc.profile.IfBuildProfile("test")
public class TestDelivery implements DeliveryChannel {
    public String name() { return "test"; }
}
