package org.treblereel.mcp.fixture.gradle;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {
    public String greeting() {
        return "hello";
    }
}
