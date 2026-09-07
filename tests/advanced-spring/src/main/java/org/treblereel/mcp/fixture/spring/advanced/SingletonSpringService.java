package org.treblereel.mcp.fixture.spring.advanced;

import jakarta.inject.Singleton;
import org.springframework.stereotype.Service;

@Service
@Singleton
public class SingletonSpringService {
    public void doWork() {}
}
