package org.treblereel.mcp.fixture.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
public class AppConfig {

    @Bean
    public CacheManager cacheManager() {
        return new CacheManager();
    }

    @Bean
    @Scope("prototype")
    public RequestContext requestContext() {
        return new RequestContext();
    }
}
