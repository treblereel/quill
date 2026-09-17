package org.treblereel.mcp.model;

import java.util.List;

public record ResolutionCandidate(
        Integer beanId,
        String className,
        Integer relatedBeanId,
        CandidateDisposition disposition,
        String reason,
        List<String> rules
) {
    public ResolutionCandidate {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public ResolutionCandidate(Integer beanId, String className,
            CandidateDisposition disposition, String reason, List<String> rules) {
        this(beanId, className, null, disposition, reason, rules);
    }

    public ResolutionCandidate withBeanIds(Integer newBeanId, Integer newRelatedBeanId) {
        return new ResolutionCandidate(newBeanId, className, newRelatedBeanId,
                disposition, reason, rules);
    }
}
