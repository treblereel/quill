package org.treblereel.mcp.fixture.gradle;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EmailNotificationService implements NotificationService {
    @Override
    public void notifyUser() {
    }
}
