package org.acme.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.common.NotificationService;

@ApplicationScoped
public class EmailNotificationService implements NotificationService {
    @Override
    public void send(String message) {}
}
