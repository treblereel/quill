package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class UnknownLookup {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment environment;
    public java.util.Optional<String> lookup(String key) {
        return java.util.Optional.ofNullable(environment.getProperty(key));
    }
}
