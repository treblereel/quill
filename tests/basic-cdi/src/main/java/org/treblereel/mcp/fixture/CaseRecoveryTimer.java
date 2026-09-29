package org.treblereel.mcp.fixture;

public class CaseRecoveryTimer {
    public void recover(CaseFlowState state) {
        if (state.dispatchCount > 0) state.recovered = true;
    }
}
