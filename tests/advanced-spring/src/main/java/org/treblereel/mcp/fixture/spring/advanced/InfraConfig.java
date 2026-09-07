package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;

@Configuration
public class InfraConfig {

    @Bean
    @Primary
    public ConnectionPool mainPool() {
        return new ConnectionPool("main");
    }

    @Bean(name = {"temporaryPool", "poolAlias"})
    @Profile("prod")
    @Scope("prototype")
    public ConnectionPool tempPool() {
        return new ConnectionPool("temp");
    }

    @Bean
    public String poolInfo(ConnectionPool pool) {
        return pool.getName();
    }
}
