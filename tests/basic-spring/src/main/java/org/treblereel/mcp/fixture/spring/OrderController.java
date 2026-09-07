package org.treblereel.mcp.fixture.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final UserService userService;

    @Autowired
    public OrderController(UserService userService) {
        this.userService = userService;
    }

    public String handleOrder(String userId) {
        userService.createUser(userId);
        return "ok";
    }
}
