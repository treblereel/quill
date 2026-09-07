package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private AuditLog auditLog;

    @Autowired
    public void setAuditLog(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public void audit(String event) {
        auditLog.record(event);
    }
}
