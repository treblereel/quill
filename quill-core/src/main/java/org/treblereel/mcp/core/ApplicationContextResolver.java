package org.treblereel.mcp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.CandidateDisposition;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.ModuleClasspathRecord;
import org.treblereel.mcp.model.ResolutionCandidate;
import org.treblereel.mcp.model.ResolutionConfidence;
import org.treblereel.mcp.model.ResolutionStatus;
import org.treblereel.mcp.model.ResolutionTrace;

/** Re-evaluates static DI candidates in each application module's visible classpath. */
public final class ApplicationContextResolver {

    private ApplicationContextResolver() {}

    public static List<InjectionPointRecord> refine(
            List<InjectionPointRecord> injectionPoints, List<BeanRecord> beans,
            List<ClassRecord> classes, List<ClassOccurrenceRecord> occurrences,
            List<ModuleClasspathRecord> moduleClasspath) {
        Map<Integer, BeanRecord> beansById = new HashMap<>();
        beans.forEach(bean -> beansById.put(bean.id(), bean));
        Map<Integer, ClassRecord> classesById = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) classesById.put(i + 1, classes.get(i));
        Map<Integer, Set<String>> modulesByClass = modulesByClass(classesById, occurrences);
        Map<String, Set<String>> visibleByApplication = new LinkedHashMap<>();
        for (ModuleClasspathRecord entry : moduleClasspath) {
            visibleByApplication.computeIfAbsent(entry.applicationModule(), ignored -> new LinkedHashSet<>())
                    .add(entry.visibleModule());
        }

        List<InjectionPointRecord> result = new ArrayList<>(injectionPoints.size());
        for (InjectionPointRecord point : injectionPoints) {
            BeanRecord owner = beansById.get(point.beanId());
            Set<String> ownerModules = owner == null ? Set.of()
                    : modulesByClass.getOrDefault(owner.classId(), Set.of());
            List<String> contexts = visibleByApplication.entrySet().stream()
                    .filter(entry -> entry.getValue().stream().anyMatch(ownerModules::contains))
                    .map(Map.Entry::getKey).sorted().toList();
            if (contexts.isEmpty()) {
                result.add(point);
                continue;
            }
            List<InjectionPointRecord> contextual = contexts.stream()
                    .map(context -> resolve(point, beans, classesById, modulesByClass,
                            visibleByApplication.get(context), context))
                    .toList();
            InjectionPointRecord first = contextual.getFirst();
            boolean consistent = contextual.stream().allMatch(candidate -> sameOutcome(first, candidate));
            result.add(consistent ? first : contextRequired(point, contexts, contextual));
        }
        return List.copyOf(result);
    }

    private static InjectionPointRecord resolve(
            InjectionPointRecord point, List<BeanRecord> beans,
            Map<Integer, ClassRecord> classesById, Map<Integer, Set<String>> modulesByClass,
            Set<String> visibleModules, String context) {
        boolean spring = InjectionPointRecord.STATIC_SPRING.equals(point.resolutionStrategy());
        List<BeanRecord> candidates = beans.stream()
                .filter(bean -> bean.beanTypes() != null && bean.beanTypes().contains(point.targetType()))
                .filter(bean -> modulesByClass.getOrDefault(bean.classId(), Set.of()).stream()
                        .anyMatch(visibleModules::contains))
                .filter(bean -> qualifiersMatch(point.qualifiers(), bean.qualifiers(), spring))
                .toList();
        if (spring) {
            List<BeanRecord> primaries = candidates.stream().filter(BeanRecord::isAlternative).toList();
            if (candidates.size() > 1 && primaries.size() == 1) candidates = primaries;
        } else {
            candidates = applySpecialization(candidates, point.resolutionTrace());
            List<BeanRecord> alternatives = candidates.stream()
                    .filter(bean -> bean.isAlternative() && bean.priority() != null).toList();
            if (!alternatives.isEmpty()) {
                int best = alternatives.stream().mapToInt(BeanRecord::priority).max().orElseThrow();
                candidates = alternatives.stream().filter(bean -> bean.priority() == best).toList();
            } else {
                candidates = candidates.stream().filter(bean -> !bean.isAlternative()).toList();
            }
        }

        Integer selected = candidates.size() == 1 ? candidates.getFirst().id() : null;
        ResolutionStatus status = selected != null ? ResolutionStatus.RESOLVED
                : candidates.size() > 1 ? ResolutionStatus.AMBIGUOUS : ResolutionStatus.UNKNOWN;
        String reason = selected != null ? "UNIQUE_CONTEXT_CANDIDATE"
                : candidates.size() > 1 ? "MULTIPLE_CONTEXT_CANDIDATES"
                : "NO_CONTEXT_CANDIDATE";
        Set<Integer> eligible = candidates.stream().map(BeanRecord::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ResolutionCandidate> traceCandidates = beans.stream()
                .filter(bean -> bean.beanTypes() != null && bean.beanTypes().contains(point.targetType()))
                .map(bean -> new ResolutionCandidate(bean.id(), className(classesById, bean.classId()),
                        java.util.Objects.equals(bean.id(), selected) ? CandidateDisposition.SELECTED
                                : eligible.contains(bean.id()) ? CandidateDisposition.ELIGIBLE
                                : CandidateDisposition.EXCLUDED,
                        java.util.Objects.equals(bean.id(), selected) ? "UNIQUE_CONTEXT_CANDIDATE"
                                : eligible.contains(bean.id()) ? "CONTEXT_CANDIDATE"
                                : visible(visibleModules, modulesByClass.get(bean.classId()))
                                        ? "FRAMEWORK_RULE_EXCLUDED" : "MODULE_NOT_VISIBLE",
                        List.of("APPLICATION_MODULE_CLASSPATH")))
                .toList();
        List<String> rules = new ArrayList<>(point.resolutionTrace().appliedRules());
        if (!rules.contains("APPLICATION_MODULE_CLASSPATH")) rules.add("APPLICATION_MODULE_CLASSPATH");
        List<String> limitations = new ArrayList<>(point.limitations());
        limitations.add("Resolved in application module context '" + context + "'.");
        return new InjectionPointRecord(point.id(), point.beanId(), point.kind(), point.targetType(),
                point.qualifiers(), point.fieldName(), selected, status, point.resolutionStrategy(),
                reason, selected != null ? ResolutionConfidence.HIGH
                        : candidates.size() > 1 ? ResolutionConfidence.MEDIUM : ResolutionConfidence.LOW,
                limitations, new ResolutionTrace(traceCandidates, rules,
                        point.resolutionTrace().unsupportedRules()));
    }

    private static List<BeanRecord> applySpecialization(
            List<BeanRecord> candidates, ResolutionTrace trace) {
        Set<Integer> candidateIds = candidates.stream().map(BeanRecord::id).collect(
                java.util.stream.Collectors.toSet());
        Set<Integer> specialized = new LinkedHashSet<>();
        for (ResolutionCandidate evidence : trace.candidates()) {
            if ("SPECIALIZED_BY".equals(evidence.reason()) && evidence.beanId() != null
                    && evidence.relatedBeanId() != null
                    && candidateIds.contains(evidence.relatedBeanId())) {
                specialized.add(evidence.beanId());
            }
        }
        return candidates.stream().filter(bean -> !specialized.contains(bean.id())).toList();
    }

    private static boolean qualifiersMatch(
            List<String> required, List<String> available, boolean spring) {
        for (String qualifier : required) {
            if ((spring && qualifier.equals("@Default")) || qualifier.equals("@Any")) continue;
            if (!available.contains(qualifier)) return false;
        }
        return true;
    }

    private static Map<Integer, Set<String>> modulesByClass(
            Map<Integer, ClassRecord> classesById, List<ClassOccurrenceRecord> occurrences) {
        Map<Integer, Set<String>> result = new HashMap<>();
        for (ClassOccurrenceRecord occurrence : occurrences) {
            result.computeIfAbsent(occurrence.classId(), ignored -> new LinkedHashSet<>())
                    .add(occurrence.module());
        }
        classesById.forEach((id, cls) -> {
            if (cls.module() != null) result.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                    .add(cls.module());
        });
        return result;
    }

    private static boolean visible(Set<String> visibleModules, Set<String> candidateModules) {
        return candidateModules != null && candidateModules.stream().anyMatch(visibleModules::contains);
    }

    private static String className(Map<Integer, ClassRecord> classesById, int classId) {
        ClassRecord cls = classesById.get(classId);
        return cls == null ? null : cls.className();
    }

    private static boolean sameOutcome(InjectionPointRecord left, InjectionPointRecord right) {
        return left.resolutionStatus() == right.resolutionStatus()
                && java.util.Objects.equals(left.resolvedBeanId(), right.resolvedBeanId());
    }

    private static InjectionPointRecord contextRequired(InjectionPointRecord original,
            List<String> contexts, List<InjectionPointRecord> outcomes) {
        List<String> limitations = new ArrayList<>(original.limitations());
        limitations.add("Resolution differs across application contexts: " + contexts + ".");
        List<String> rules = new ArrayList<>(original.resolutionTrace().appliedRules());
        if (!rules.contains("APPLICATION_MODULE_CLASSPATH")) rules.add("APPLICATION_MODULE_CLASSPATH");
        Set<String> unsupported = new LinkedHashSet<>(original.resolutionTrace().unsupportedRules());
        unsupported.add("APPLICATION_CONTEXT_SELECTION");
        List<ResolutionCandidate> candidates = outcomes.stream()
                .flatMap(outcome -> outcome.resolutionTrace().candidates().stream())
                .collect(java.util.stream.Collectors.toMap(
                        ResolutionCandidate::beanId, candidate -> candidate, (left, right) -> left,
                        LinkedHashMap::new)).values().stream().toList();
        return new InjectionPointRecord(original.id(), original.beanId(), original.kind(),
                original.targetType(), original.qualifiers(), original.fieldName(), null,
                ResolutionStatus.CONTEXT_REQUIRED, "APPLICATION_CONTEXT",
                "APPLICATION_CONTEXT_REQUIRED", ResolutionConfidence.LOW, limitations,
                new ResolutionTrace(candidates, rules, List.copyOf(unsupported)));
    }
}
