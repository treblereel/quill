package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PoolConsumer {

    @Autowired
    private ConnectionPool connectionPool;

    public String getPoolName() {
        return connectionPool.getName();
    }
}
