package org.treblereel.mcp.fixture.spring.advanced;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CdiScopedBean {
    public String hello() {
        return "cdi";
    }
}
