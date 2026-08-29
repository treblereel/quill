package org.acme.service;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.acme.common.NotificationService;

@ApplicationScoped
@Alternative
@Priority(1)
public class SmsNotificationService implements NotificationService {
    @Override
    public void send(String message) {}
}
