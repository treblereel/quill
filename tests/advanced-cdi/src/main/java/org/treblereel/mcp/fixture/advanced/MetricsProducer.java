package org.treblereel.mcp.fixture.advanced;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class MetricsProducer {

    @Produces
    public MetricsConfig metricsConfig = new MetricsConfig("http://localhost:9090");
}
