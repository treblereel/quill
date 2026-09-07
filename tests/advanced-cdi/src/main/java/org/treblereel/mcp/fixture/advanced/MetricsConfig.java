package org.treblereel.mcp.fixture.advanced;

public class MetricsConfig {
    private final String endpoint;

    public MetricsConfig(String endpoint) {
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }
}
