package org.treblereel.mcp.fixture.spring;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class EmailNotificationService implements NotificationService {

    @Override
    public void send(String message) {
        // send email
    }
}
