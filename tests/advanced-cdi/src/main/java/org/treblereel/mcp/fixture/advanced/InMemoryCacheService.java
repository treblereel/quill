package org.treblereel.mcp.fixture.advanced;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InMemoryCacheService implements CacheService {
    @Override
    public String get(String key) {
        return null;
    }
}
