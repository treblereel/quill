package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UnknownLookup {

    public java.util.Optional<String> lookup(String key) {
        return org.eclipse.microprofile.config.ConfigProvider.getConfig().getOptionalValue(key, String.class);
    }
}
