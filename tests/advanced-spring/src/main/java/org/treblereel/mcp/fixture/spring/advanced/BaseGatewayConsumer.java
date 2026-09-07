package org.treblereel.mcp.fixture.spring.advanced;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BaseGatewayConsumer {

    @Autowired
    @Qualifier("stripe")
    private BaseGateway gateway;
}
