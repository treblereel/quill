package org.treblereel.mcp.fixture.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final UserService userService;

    @Value("${orders.region:global}")
    private String region;

    @Autowired
    public OrderController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/{userId}")
    public String handleOrder(String userId) {
        userService.createUser(userId);
        return "ok";
    }
}
