package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;

@DomainService
public class OrderProcessor {

    @Autowired
    private AuditLog auditLog;

    public void process() {
        auditLog.toString();
    }
}
