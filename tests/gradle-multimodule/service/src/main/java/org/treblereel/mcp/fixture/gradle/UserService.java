package org.treblereel.mcp.fixture.gradle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserService {
    @Inject NotificationService notificationService;
}
