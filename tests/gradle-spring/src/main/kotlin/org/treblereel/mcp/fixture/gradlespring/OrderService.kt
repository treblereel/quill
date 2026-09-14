package org.treblereel.mcp.fixture.gradlespring

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class OrderService {
    @field:Autowired
    lateinit var repository: OrderRepository
}
