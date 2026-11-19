package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DiagnosticProbe {
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<AuditPort> audit;
    public boolean isUnsatisfied() { return audit.isUnsatisfied(); }
}
