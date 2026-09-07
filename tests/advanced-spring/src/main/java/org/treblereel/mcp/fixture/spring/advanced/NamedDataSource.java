package org.treblereel.mcp.fixture.spring.advanced;

import jakarta.inject.Named;
import org.springframework.stereotype.Component;

@Component
@Named("primary-db")
public class NamedDataSource {
    public String getUrl() {
        return "jdbc:h2:mem:primary";
    }
}
