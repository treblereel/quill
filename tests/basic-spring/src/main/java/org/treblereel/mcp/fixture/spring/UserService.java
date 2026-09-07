package org.treblereel.mcp.fixture.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationService notificationService;

    public void createUser(String name) {
        userRepository.findById(name);
        notificationService.send("User created: " + name);
    }
}
