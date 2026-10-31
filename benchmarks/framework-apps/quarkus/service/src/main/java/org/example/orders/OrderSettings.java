package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderSettings {
    private final String prefix;
    @jakarta.inject.Inject
    public OrderSettings(@org.eclipse.microprofile.config.inject.ConfigProperty(name = "orders.prefix") String prefix) {
        this.prefix = prefix;
    }
    public String prefix() { return prefix; }
}
