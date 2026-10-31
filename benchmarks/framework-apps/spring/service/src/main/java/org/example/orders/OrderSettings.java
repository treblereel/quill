package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class OrderSettings {
    private final String prefix;
    @org.springframework.beans.factory.annotation.Autowired
    public OrderSettings(@org.springframework.beans.factory.annotation.Value("${orders.prefix}") String prefix) {
        this.prefix = prefix;
    }
    public String prefix() { return prefix; }
}
