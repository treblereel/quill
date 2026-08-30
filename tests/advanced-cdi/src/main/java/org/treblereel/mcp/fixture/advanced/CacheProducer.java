package org.treblereel.mcp.fixture.advanced;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CacheProducer {

    @Produces
    @Cached
    public CacheService cachedService() {
        return key -> "cached:" + key;
    }
}
