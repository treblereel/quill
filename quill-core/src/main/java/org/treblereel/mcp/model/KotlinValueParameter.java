package org.treblereel.mcp.model;

/** One source value parameter; excludes dispatch/extension receivers and Continuation. */
public record KotlinValueParameter(String name, boolean hasDefault, boolean vararg) {}
