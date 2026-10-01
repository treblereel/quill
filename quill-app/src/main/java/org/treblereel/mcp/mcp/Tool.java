package org.treblereel.mcp.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Tool {
    String description();

    /** Whether this tool returns a JSON object that should also be exposed as structured content. */
    boolean structured() default false;

    /** Optional identifier for a more specific output schema. */
    String output() default "";
}
