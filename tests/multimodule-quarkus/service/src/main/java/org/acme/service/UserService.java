package org.acme.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.common.NotificationService;

@ApplicationScoped
public class UserService {
    @Inject
    NotificationService notificationService;

    public void notifyUser(String message) {
        notificationService.send(message);
    }
}
