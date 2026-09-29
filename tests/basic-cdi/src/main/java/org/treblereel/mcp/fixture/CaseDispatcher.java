package org.treblereel.mcp.fixture;

public class CaseDispatcher {
    public void dispatch(CaseFlowState state) {
        if (!state.persisted) throw new IllegalStateException("state must be durable");
        state.dispatchCount++;
    }
}
