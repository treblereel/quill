package org.treblereel.mcp.fixture.advanced;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class SettingsProducer {

    @Produces
    public java.util.Properties appSettings = new java.util.Properties();
}
