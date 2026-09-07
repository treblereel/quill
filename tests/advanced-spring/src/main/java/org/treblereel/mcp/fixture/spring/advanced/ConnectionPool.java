package org.treblereel.mcp.fixture.spring.advanced;

public class ConnectionPool {

    private final String name;
    private AuditLog auditLog;

    public ConnectionPool(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }
}
