package org.treblereel.mcp.fixture.gradle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GreetingResource {
    @Inject GreetingService greetingService;

    public String greeting() {
        return greetingService.greeting();
    }
}
