package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class LiteModeComponent {

    @Bean
    public ConnectionPool liteModePool() {
        return new ConnectionPool("lite");
    }
}
