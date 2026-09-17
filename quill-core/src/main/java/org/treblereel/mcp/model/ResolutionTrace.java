package org.treblereel.mcp.model;

import java.util.List;

public record ResolutionTrace(
        List<ResolutionCandidate> candidates,
        List<String> appliedRules,
        List<String> unsupportedRules
) {
    public static final ResolutionTrace EMPTY = new ResolutionTrace(
            List.of(), List.of(), List.of());

    public ResolutionTrace {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        appliedRules = appliedRules == null ? List.of() : List.copyOf(appliedRules);
        unsupportedRules = unsupportedRules == null
                ? List.of() : List.copyOf(unsupportedRules);
    }
}
