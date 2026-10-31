package org.treblereel.mcp.model;

import java.util.List;

public record InjectionPointRecord(
        int id,
        int beanId,
        String kind,
        String targetType,
        List<String> qualifiers,
        String fieldName,
        Integer resolvedBeanId,
        ResolutionStatus resolutionStatus,
        String resolutionStrategy,
        ResolutionReason resolutionReason,
        ResolutionConfidence resolutionConfidence,
        List<String> limitations,
        ResolutionTrace resolutionTrace
) {
    public static final String STATIC_CDI = "STATIC_CDI";
    public static final String STATIC_SPRING = "STATIC_SPRING";

    public InjectionPointRecord {
        qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        resolutionTrace = resolutionTrace == null ? ResolutionTrace.EMPTY : resolutionTrace;
    }

    public static InjectionPointRecord staticAnalysis(int id, int beanId, String kind,
            String targetType, List<String> qualifiers, String fieldName,
            Integer resolvedBeanId, boolean ambiguous, String strategy) {
        ResolutionStatus status;
        ResolutionReason reason;
        ResolutionConfidence confidence;
        if (resolvedBeanId != null) {
            status = ResolutionStatus.RESOLVED;
            reason = ResolutionReason.UNIQUE_STATIC_CANDIDATE;
            confidence = ResolutionConfidence.HIGH;
        } else if (ambiguous) {
            status = ResolutionStatus.AMBIGUOUS;
            reason = ResolutionReason.MULTIPLE_STATIC_CANDIDATES;
            confidence = ResolutionConfidence.MEDIUM;
        } else {
            status = ResolutionStatus.UNKNOWN;
            reason = ResolutionReason.NO_STATIC_CANDIDATE;
            confidence = ResolutionConfidence.LOW;
        }
        return new InjectionPointRecord(id, beanId, kind, targetType, qualifiers, fieldName,
                resolvedBeanId, status, strategy, reason, confidence,
                List.of(ResolutionMessages.STATIC_MODEL_LIMITATION), ResolutionTrace.EMPTY);
    }

    public InjectionPointRecord withId(int newId) {
        return new InjectionPointRecord(newId, beanId, kind, targetType, qualifiers, fieldName,
                resolvedBeanId, resolutionStatus, resolutionStrategy, resolutionReason,
                resolutionConfidence, limitations, resolutionTrace);
    }

    public InjectionPointRecord withResolution(Integer newResolvedBeanId,
            boolean ambiguous, ResolutionTrace trace) {
        InjectionPointRecord resolved = staticAnalysis(id, beanId, kind, targetType,
                qualifiers, fieldName, newResolvedBeanId, ambiguous, resolutionStrategy);
        return new InjectionPointRecord(resolved.id(), resolved.beanId(), resolved.kind(),
                resolved.targetType(), resolved.qualifiers(), resolved.fieldName(),
                resolved.resolvedBeanId(), resolved.resolutionStatus(),
                resolved.resolutionStrategy(), resolved.resolutionReason(),
                resolved.resolutionConfidence(), resolved.limitations(), trace);
    }
}
