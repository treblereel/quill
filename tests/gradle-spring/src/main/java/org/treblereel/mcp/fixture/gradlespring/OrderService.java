package org.treblereel.mcp.fixture.gradlespring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    @Autowired OrderRepository repository;
}
