package org.treblereel.mcp.fixture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DataSourceConsumer {

    @Inject
    javax.sql.DataSource dataSource;
}
