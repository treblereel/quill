package org.treblereel.mcp.fixture.spring.advanced;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.web.context.annotation.RequestScope;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@BusinessComponent
@RequestScope
public @interface DomainService {
}
