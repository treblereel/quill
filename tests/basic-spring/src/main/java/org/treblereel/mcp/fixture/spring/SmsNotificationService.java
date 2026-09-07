package org.treblereel.mcp.fixture.spring;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("sms")
public class SmsNotificationService implements NotificationService {

    @Override
    public void send(String message) {
        // send SMS
    }
}
