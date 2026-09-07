package org.treblereel.mcp.fixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DatabaseConfig {

    @Produces
    public javax.sql.DataSource dataSource() {
        return null;
    }

    @Produces
    public OrderDTO orderDto() {
        return new OrderDTO("default");
    }
}
