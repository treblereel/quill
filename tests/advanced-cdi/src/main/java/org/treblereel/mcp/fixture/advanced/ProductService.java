package org.treblereel.mcp.fixture.advanced;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Logged
@ApplicationScoped
public class ProductService {

    @Inject
    CacheService defaultCache;

    @Inject
    @Cached
    CacheService qualifiedCache;

    public String getProduct(String id) {
        return qualifiedCache.get(id);
    }
}
