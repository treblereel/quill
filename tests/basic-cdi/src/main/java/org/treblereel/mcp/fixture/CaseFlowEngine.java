package org.treblereel.mcp.fixture;

import java.util.concurrent.Executor;

public class CaseFlowEngine {
    private final CaseRepository repository;
    private final CaseDispatcher dispatcher;
    private final CaseRecoveryTimer recoveryTimer;
    private final Executor executor;

    public CaseFlowEngine(CaseRepository repository, CaseDispatcher dispatcher,
            CaseRecoveryTimer recoveryTimer, Executor executor) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.recoveryTimer = recoveryTimer;
        this.executor = executor;
    }

    public void execute(CaseFlowState state) {
        repository.persist(state);
        dispatcher.dispatch(state);
        executor.execute(() -> recoveryTimer.recover(state));
    }
}
