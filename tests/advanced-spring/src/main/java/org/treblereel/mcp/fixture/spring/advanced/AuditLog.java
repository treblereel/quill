package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.stereotype.Repository;

@Repository
public class AuditLog {
    public void record(String event) {}
}
