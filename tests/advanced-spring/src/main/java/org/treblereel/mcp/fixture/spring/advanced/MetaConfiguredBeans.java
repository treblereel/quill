package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.context.annotation.Bean;

@EnableInfra
public class MetaConfiguredBeans {

    @Bean
    public ConnectionPool metaPool() {
        return new ConnectionPool("meta");
    }
}
