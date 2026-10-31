package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class DiagnosticProbe {
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.beans.factory.ObjectProvider<AuditPort> audit;
    public boolean isUnsatisfied() { return audit.stream().findAny().isEmpty(); }
}
