package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReportGenerator {

    @Autowired
    private AuditLog auditLog;

    private ExternalApiClient apiClient;

    public String generate() {
        return "report";
    }
}
