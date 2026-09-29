package org.treblereel.mcp.fixture;

public class CaseRepository {
    public void persist(CaseFlowState state) {
        state.persisted = true;
    }
}
