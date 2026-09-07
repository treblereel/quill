package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.stereotype.Controller;

@Controller
public class DashboardController {

    private final AuditService auditService;
    private final CheckoutService checkoutService;

    public DashboardController(AuditService auditService, CheckoutService checkoutService) {
        this.auditService = auditService;
        this.checkoutService = checkoutService;
    }

    public String dashboard() {
        return "dashboard";
    }
}
