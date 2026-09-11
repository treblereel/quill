package org.treblereel.mcp.fixture.application;

public final class ImperativeConsumer {
    public Object create() {
        return new ConstructorOnlyDependency();
    }
}
