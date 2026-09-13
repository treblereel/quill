package org.treblereel.mcp.core;

import java.util.*;
import org.jboss.jandex.*;
import org.treblereel.mcp.model.*;

public final class SpringResolver {

    private SpringResolver() {}

    private static final DotName COMPONENT =
            DotName.createSimple("org.springframework.stereotype.Component");
    private static final DotName SERVICE =
            DotName.createSimple("org.springframework.stereotype.Service");
    private static final DotName REPOSITORY =
            DotName.createSimple("org.springframework.stereotype.Repository");
    private static final DotName CONTROLLER =
            DotName.createSimple("org.springframework.stereotype.Controller");
    private static final DotName REST_CONTROLLER =
            DotName.createSimple("org.springframework.web.bind.annotation.RestController");
    private static final DotName CONFIGURATION =
            DotName.createSimple("org.springframework.context.annotation.Configuration");

    private static final Set<DotName> BEAN_ANNOTATIONS = Set.of(
            COMPONENT, SERVICE, REPOSITORY, CONTROLLER, REST_CONTROLLER, CONFIGURATION
    );

    private static final DotName BEAN_METHOD =
            DotName.createSimple("org.springframework.context.annotation.Bean");
    private static final DotName SCOPE =
            DotName.createSimple("org.springframework.context.annotation.Scope");
    private static final DotName PRIMARY =
            DotName.createSimple("org.springframework.context.annotation.Primary");
    private static final DotName PROFILE =
            DotName.createSimple("org.springframework.context.annotation.Profile");
    private static final DotName QUALIFIER =
            DotName.createSimple("org.springframework.beans.factory.annotation.Qualifier");
    private static final DotName NAMED =
            DotName.createSimple("jakarta.inject.Named");
    private static final DotName NAMED_JAVAX =
            DotName.createSimple("javax.inject.Named");
    private static final DotName AUTOWIRED =
            DotName.createSimple("org.springframework.beans.factory.annotation.Autowired");
    private static final DotName INJECT =
            DotName.createSimple("jakarta.inject.Inject");
    private static final DotName INJECT_JAVAX =
            DotName.createSimple("javax.inject.Inject");

    record BeanCandidate(int beanId, int classId, List<String> qualifiers, boolean isPrimary) {}

    record Resolution(Integer beanId, Integer classId, boolean isAmbiguous) {
        static Resolution unique(BeanCandidate c) { return new Resolution(c.beanId(), c.classId(), false); }
        static Resolution ambiguous() { return new Resolution(null, null, true); }
        static Resolution unsatisfied() { return new Resolution(null, null, false); }
    }

    public static boolean isSpringProject(Index index) {
        for (ClassInfo ci : index.getKnownClasses()) {
            if (findBeanAnnotation(ci, index) != null) return true;
        }
        return false;
    }

    public static BeanResolver.ResolutionResult resolve(Index applicationIndex) {
        return resolve(applicationIndex, (IndexView) null);
    }

    public static BeanResolver.ResolutionResult resolve(Index applicationIndex, Index dependencyIndex) {
        return resolve(applicationIndex, (IndexView) dependencyIndex);
    }

    public static BeanResolver.ResolutionResult resolve(Index applicationIndex, IndexView dependencyIndex) {
        IndexView lookupIndex = dependencyIndex != null
                ? CompositeIndex.create(applicationIndex, dependencyIndex)
                : applicationIndex;

        Map<String, Integer> classNameToId = new HashMap<>();
        List<BeanRecord> beanRecords = new ArrayList<>();
        List<InjectionPointRecord> ipRecords = new ArrayList<>();
        List<DependencyRecord> depRecords = new ArrayList<>();
        List<CdiProblem> problems = new ArrayList<>();

        int beanIdSeq = 1;
        int ipIdSeq = 1;
        Map<String, List<BeanCandidate>> candidatesByType = new HashMap<>();
        List<ClassInfo> beanClasses = new ArrayList<>();
        List<Integer> beanClassIds = new ArrayList<>();
        List<Integer> beanIds = new ArrayList<>();

        record ProducerMethod(MethodInfo method, int configClassId, int configBeanId) {}
        List<ProducerMethod> producerMethods = new ArrayList<>();

        // Pass 1: discover all beans and build the candidate map
        for (ClassInfo ci : applicationIndex.getKnownClasses()) {
            DotName beanAnnotation = findBeanAnnotation(ci, lookupIndex);
            if (beanAnnotation == null) continue;

            int classId = classNameToId.computeIfAbsent(
                    ci.name().toString(), k -> classNameToId.size() + 1);

            String scope = extractScope(ci, lookupIndex);
            List<String> qualifiers = extractQualifiers(ci, lookupIndex);
            List<String> profiles = extractProfiles(ci.declaredAnnotations(), lookupIndex);
            boolean isPrimary = findAnnotationOrMeta(
                    ci.declaredAnnotations(), PRIMARY, lookupIndex) != null;
            List<String> beanTypes = extractBeanTypes(ci, lookupIndex);

            int beanId = beanIdSeq++;
            beanRecords.add(new BeanRecord(
                    beanId, classId, "CLASS", scope, qualifiers, List.of(),
                    isPrimary, isPrimary ? 0 : null,
                    profiles.isEmpty() ? null : profiles,
                    null, null, beanTypes));

            BeanCandidate candidate = new BeanCandidate(beanId, classId, qualifiers, isPrimary);
            for (String type : beanTypes) {
                candidatesByType.computeIfAbsent(type, k -> new ArrayList<>()).add(candidate);
            }

            beanClasses.add(ci);
            beanClassIds.add(classId);
            beanIds.add(beanId);

            if (hasBeanMethods(ci)) {
                for (MethodInfo method : ci.methods()) {
                    if (!method.hasAnnotation(BEAN_METHOD)) continue;

                    int producerBeanId = beanIdSeq++;
                    String producerScope = extractMethodScope(method, scope, lookupIndex);
                    List<String> producerQualifiers = extractMethodQualifiers(method, lookupIndex);
                    boolean producerPrimary = findAnnotationOrMeta(
                            methodLevelAnnotations(method), PRIMARY, lookupIndex) != null;
                    List<String> producerBeanTypes = buildProducerBeanTypes(method, lookupIndex);
                    List<String> producerProfiles = mergeProfiles(
                            profiles, extractProfiles(methodLevelAnnotations(method), lookupIndex));

                    beanRecords.add(new BeanRecord(
                            producerBeanId, classId, "PRODUCER_METHOD",
                            producerScope, producerQualifiers, List.of(),
                            producerPrimary, producerPrimary ? 0 : null,
                            producerProfiles.isEmpty() ? null : producerProfiles,
                            classId, method.name(), producerBeanTypes));

                    BeanCandidate producerCandidate = new BeanCandidate(
                            producerBeanId, classId, producerQualifiers, producerPrimary);
                    for (String type : producerBeanTypes) {
                        candidatesByType.computeIfAbsent(type, k -> new ArrayList<>()).add(producerCandidate);
                    }

                    producerMethods.add(new ProducerMethod(method, classId, beanId));
                }
            }
        }

        // Pass 2: resolve injection points
        for (int i = 0; i < beanClasses.size(); i++) {
            ipIdSeq = resolveInjectionPoints(beanClasses.get(i), beanClassIds.get(i),
                    beanIds.get(i), lookupIndex, candidatesByType,
                    ipRecords, depRecords, ipIdSeq);
        }

        for (ProducerMethod pm : producerMethods) {
            ipIdSeq = resolveBeanMethodParams(pm.method(), pm.configClassId(), pm.configBeanId(),
                    lookupIndex, candidatesByType,
                    ipRecords, depRecords, ipIdSeq);
        }

        BeanResolver.addNonBeanDependencies(applicationIndex, beanRecords, depRecords, classNameToId);

        return new BeanResolver.ResolutionResult(
                beanRecords, ipRecords, depRecords, classNameToId, problems);
    }

    private static int resolveInjectionPoints(ClassInfo ci, int classId, int beanId,
            IndexView index, Map<String, List<BeanCandidate>> candidatesByType,
            List<InjectionPointRecord> ipRecords, List<DependencyRecord> depRecords,
            int ipIdSeq) {

        for (FieldInfo field : ci.fields()) {
            if (!isInjectionField(field)) continue;

            int ipId = ipIdSeq++;
            String targetType = field.type().name().toString();
            List<String> ipQualifiers = extractFieldQualifiers(field, index);
            Resolution r = resolveByType(targetType, ipQualifiers, candidatesByType);

            ipRecords.add(new InjectionPointRecord(
                    ipId, beanId, "FIELD", targetType, ipQualifiers,
                    field.name(), r.beanId(), r.isAmbiguous()));

            if (r.classId() != null) {
                depRecords.add(new DependencyRecord(classId, r.classId(), "SPRING_INJECT", ipId));
            }
        }

        MethodInfo injectConstructor = findInjectConstructor(ci);
        if (injectConstructor != null) {
            for (int i = 0; i < injectConstructor.parametersCount(); i++) {
                int ipId = ipIdSeq++;
                String targetType = injectConstructor.parameterType(i).name().toString();
                List<String> ipQualifiers = extractParamQualifiers(injectConstructor, i, index);
                Resolution r = resolveByType(targetType, ipQualifiers, candidatesByType);

                ipRecords.add(new InjectionPointRecord(
                        ipId, beanId, "CONSTRUCTOR_PARAM", targetType, ipQualifiers,
                        "<init>", r.beanId(), r.isAmbiguous()));

                if (r.classId() != null) {
                    depRecords.add(new DependencyRecord(classId, r.classId(), "SPRING_INJECT", ipId));
                }
            }
        }

        for (MethodInfo method : ci.methods()) {
            if (method.name().equals("<init>") || method.name().equals("<clinit>")) continue;
            if (!method.hasAnnotation(AUTOWIRED)) continue;

            for (int i = 0; i < method.parametersCount(); i++) {
                int ipId = ipIdSeq++;
                String targetType = method.parameterType(i).name().toString();
                List<String> ipQualifiers = extractParamQualifiers(method, i, index);
                Resolution r = resolveByType(targetType, ipQualifiers, candidatesByType);

                ipRecords.add(new InjectionPointRecord(
                        ipId, beanId, "METHOD_PARAM", targetType, ipQualifiers,
                        method.name(), r.beanId(), r.isAmbiguous()));

                if (r.classId() != null) {
                    depRecords.add(new DependencyRecord(classId, r.classId(), "SPRING_INJECT", ipId));
                }
            }
        }

        return ipIdSeq;
    }

    private static int resolveBeanMethodParams(MethodInfo method, int configClassId, int configBeanId,
            IndexView index, Map<String, List<BeanCandidate>> candidatesByType,
            List<InjectionPointRecord> ipRecords, List<DependencyRecord> depRecords,
            int ipIdSeq) {
        for (int i = 0; i < method.parametersCount(); i++) {
            int ipId = ipIdSeq++;
            String targetType = method.parameterType(i).name().toString();
            List<String> ipQualifiers = extractParamQualifiers(method, i, index);
            Resolution r = resolveByType(targetType, ipQualifiers, candidatesByType);

            ipRecords.add(new InjectionPointRecord(
                    ipId, configBeanId, "METHOD_PARAM", targetType, ipQualifiers,
                    method.name(), r.beanId(), r.isAmbiguous()));

            if (r.classId() != null) {
                depRecords.add(new DependencyRecord(configClassId, r.classId(), "SPRING_INJECT", ipId));
            }
        }
        return ipIdSeq;
    }

    static Resolution resolveByType(String targetType, List<String> qualifiers,
            Map<String, List<BeanCandidate>> candidatesByType) {
        List<BeanCandidate> candidates = candidatesByType.getOrDefault(targetType, List.of());

        if (candidates.isEmpty()) {
            return Resolution.unsatisfied();
        }

        boolean hasNonDefaultQualifier = qualifiers.stream()
                .anyMatch(q -> !q.equals("@Default"));

        if (hasNonDefaultQualifier) {
            List<BeanCandidate> matched = candidates.stream()
                    .filter(c -> matchesQualifiers(qualifiers, c.qualifiers()))
                    .toList();
            if (matched.isEmpty()) {
                return Resolution.unsatisfied();
            }
            candidates = matched;
        }

        if (candidates.size() == 1) {
            return Resolution.unique(candidates.get(0));
        }

        List<BeanCandidate> primaries = candidates.stream()
                .filter(BeanCandidate::isPrimary)
                .toList();
        if (primaries.size() == 1) {
            return Resolution.unique(primaries.get(0));
        }

        return Resolution.ambiguous();
    }

    private static boolean isInjectionField(FieldInfo field) {
        return field.hasAnnotation(AUTOWIRED)
                || field.hasAnnotation(INJECT)
                || field.hasAnnotation(INJECT_JAVAX);
    }

    private static MethodInfo findInjectConstructor(ClassInfo ci) {
        List<MethodInfo> constructors = new ArrayList<>();
        MethodInfo autowiredConstructor = null;
        for (MethodInfo m : ci.methods()) {
            if (!"<init>".equals(m.name())) continue;
            if (m.parametersCount() == 0) continue;
            if (m.hasAnnotation(AUTOWIRED) || m.hasAnnotation(INJECT) || m.hasAnnotation(INJECT_JAVAX)) {
                autowiredConstructor = m;
            }
            constructors.add(m);
        }
        if (autowiredConstructor != null) return autowiredConstructor;
        if (constructors.size() == 1) return constructors.get(0);
        return null;
    }

    private static boolean matchesQualifiers(List<String> required, List<String> beanQualifiers) {
        for (String req : required) {
            if (req.equals("@Default")) continue;
            if (beanQualifiers.stream().noneMatch(bq -> bq.equals(req))) return false;
        }
        return true;
    }

    private static boolean hasBeanMethods(ClassInfo ci) {
        for (MethodInfo method : ci.methods()) {
            if (method.hasAnnotation(BEAN_METHOD)) return true;
        }
        return false;
    }

    private static DotName findBeanAnnotation(ClassInfo ci, IndexView index) {
        if (ci.isAnnotation()) return null;
        for (DotName ann : BEAN_ANNOTATIONS) {
            if (ci.hasDeclaredAnnotation(ann)) return ann;
        }
        if (hasMetaComponentAnnotation(ci, index)) return COMPONENT;
        return null;
    }

    private static boolean hasMetaComponentAnnotation(ClassInfo ci, IndexView index) {
        for (AnnotationInstance ann : ci.declaredAnnotations()) {
            if (isBeanStereotype(ann.name(), index, new HashSet<>())) return true;
        }
        return false;
    }

    private static String extractScope(ClassInfo ci, IndexView index) {
        AnnotationInstance scopeAnn = findAnnotationOrMeta(
                ci.declaredAnnotations(), SCOPE, index);
        if (scopeAnn != null) {
            AnnotationValue value = scopeAnn.value();
            if (value != null) return mapSpringScope(value.asString());
        }
        return "@Singleton";
    }

    private static String extractMethodScope(MethodInfo method, String defaultScope, IndexView index) {
        AnnotationInstance scopeAnn = findAnnotationOrMeta(
                methodLevelAnnotations(method), SCOPE, index);
        if (scopeAnn != null) {
            AnnotationValue value = scopeAnn.value();
            if (value != null) return mapSpringScope(value.asString());
        }
        return defaultScope;
    }

    private static String mapSpringScope(String springScope) {
        return switch (springScope.toLowerCase()) {
            case "singleton" -> "@Singleton";
            case "prototype" -> "@Prototype";
            case "request" -> "@RequestScoped";
            case "session" -> "@SessionScoped";
            case "application" -> "@ApplicationScoped";
            default -> "@" + springScope;
        };
    }

    private static List<String> extractQualifiers(ClassInfo ci, IndexView index) {
        Set<String> qualifiers = new LinkedHashSet<>();

        AnnotationInstance qualifierAnn = ci.declaredAnnotation(QUALIFIER);
        if (qualifierAnn != null && qualifierAnn.value() != null) {
            qualifiers.add("@Qualifier(\"" + qualifierAnn.value().asString() + "\")");
        }

        AnnotationInstance namedAnn = ci.declaredAnnotation(NAMED);
        if (namedAnn == null) namedAnn = ci.declaredAnnotation(NAMED_JAVAX);
        if (namedAnn != null && namedAnn.value() != null) {
            qualifiers.add("@Named(\"" + namedAnn.value().asString() + "\")");
        }

        for (AnnotationInstance ann : ci.declaredAnnotations()) {
            if (isCustomQualifier(ann.name(), index)) {
                qualifiers.add(formatCustomQualifier(ann));
            }
        }

        qualifiers.add("@Default");
        for (String beanName : extractClassBeanNames(ci, index)) {
            qualifiers.add("@Qualifier(\"" + beanName + "\")");
        }

        return new ArrayList<>(qualifiers);
    }

    static String defaultBeanName(String simpleClassName) {
        if (simpleClassName.length() <= 1) return simpleClassName.toLowerCase();
        if (Character.isUpperCase(simpleClassName.charAt(0))
                && Character.isUpperCase(simpleClassName.charAt(1))) {
            return simpleClassName;
        }
        return Character.toLowerCase(simpleClassName.charAt(0)) + simpleClassName.substring(1);
    }

    private static List<String> extractMethodQualifiers(MethodInfo method, IndexView index) {
        Set<String> qualifiers = new LinkedHashSet<>();

        AnnotationInstance qualifierAnn = method.annotation(QUALIFIER);
        if (qualifierAnn != null && qualifierAnn.value() != null) {
            qualifiers.add("@Qualifier(\"" + qualifierAnn.value().asString() + "\")");
        }

        for (AnnotationInstance ann : method.annotations()) {
            if (ann.target() != null && ann.target().kind() == AnnotationTarget.Kind.METHOD) {
                if (isCustomQualifier(ann.name(), index)) {
                    qualifiers.add(formatCustomQualifier(ann));
                }
            }
        }

        qualifiers.add("@Default");

        for (String beanName : extractBeanMethodNames(method)) {
            qualifiers.add("@Qualifier(\"" + beanName + "\")");
        }

        return new ArrayList<>(qualifiers);
    }

    private static List<String> extractFieldQualifiers(FieldInfo field, IndexView index) {
        List<String> qualifiers = new ArrayList<>();

        AnnotationInstance qualifierAnn = field.annotation(QUALIFIER);
        if (qualifierAnn != null && qualifierAnn.value() != null) {
            qualifiers.add("@Qualifier(\"" + qualifierAnn.value().asString() + "\")");
        }

        AnnotationInstance namedAnn = field.annotation(NAMED);
        if (namedAnn == null) namedAnn = field.annotation(NAMED_JAVAX);
        if (namedAnn != null && namedAnn.value() != null) {
            qualifiers.add("@Named(\"" + namedAnn.value().asString() + "\")");
        }

        for (AnnotationInstance ann : field.annotations()) {
            if (isCustomQualifier(ann.name(), index)) {
                qualifiers.add(formatCustomQualifier(ann));
            }
        }

        if (qualifiers.isEmpty()) qualifiers.add("@Default");
        return qualifiers;
    }

    private static List<String> extractParamQualifiers(MethodInfo method, int paramIndex, IndexView index) {
        List<String> qualifiers = new ArrayList<>();

        for (AnnotationInstance ann : method.annotations()) {
            if (ann.target() != null
                    && ann.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER
                    && ann.target().asMethodParameter().position() == paramIndex) {
                if (ann.name().equals(QUALIFIER) && ann.value() != null) {
                    qualifiers.add("@Qualifier(\"" + ann.value().asString() + "\")");
                } else if ((ann.name().equals(NAMED) || ann.name().equals(NAMED_JAVAX))
                        && ann.value() != null) {
                    qualifiers.add("@Named(\"" + ann.value().asString() + "\")");
                } else if (isCustomQualifier(ann.name(), index)) {
                    qualifiers.add(formatCustomQualifier(ann));
                }
            }
        }

        if (qualifiers.isEmpty()) qualifiers.add("@Default");
        return qualifiers;
    }

    private static String formatCustomQualifier(AnnotationInstance ann) {
        StringBuilder sb = new StringBuilder("@").append(ann.name().withoutPackagePrefix());
        List<AnnotationValue> values = ann.values();
        if (!values.isEmpty()) {
            sb.append("(");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(", ");
                AnnotationValue v = values.get(i);
                if (values.size() == 1 && "value".equals(v.name())) {
                    sb.append(formatAnnotationValue(v));
                } else {
                    sb.append(v.name()).append("=").append(formatAnnotationValue(v));
                }
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private static String formatAnnotationValue(AnnotationValue v) {
        return switch (v.kind()) {
            case STRING -> "\"" + v.asString() + "\"";
            case BOOLEAN -> String.valueOf(v.asBoolean());
            case INTEGER -> String.valueOf(v.asInt());
            case LONG -> String.valueOf(v.asLong());
            case ENUM -> v.asEnumType().withoutPackagePrefix() + "." + v.asEnum();
            case CLASS -> v.asClass().name().withoutPackagePrefix() + ".class";
            default -> v.toString();
        };
    }

    private static boolean isCustomQualifier(DotName annotationName, IndexView index) {
        if (BEAN_ANNOTATIONS.contains(annotationName)) return false;
        if (annotationName.equals(AUTOWIRED) || annotationName.equals(INJECT)
                || annotationName.equals(INJECT_JAVAX)) return false;
        if (annotationName.equals(PRIMARY) || annotationName.equals(SCOPE)
                || annotationName.equals(PROFILE) || annotationName.equals(BEAN_METHOD)) return false;
        if (annotationName.equals(QUALIFIER) || annotationName.equals(NAMED)
                || annotationName.equals(NAMED_JAVAX)) return false;

        return hasMetaAnnotation(annotationName, QUALIFIER, index, new HashSet<>());
    }

    private static List<String> extractProfiles(
            Collection<AnnotationInstance> annotations, IndexView index) {
        AnnotationInstance profileAnn = findAnnotationOrMeta(annotations, PROFILE, index);
        if (profileAnn == null || profileAnn.value() == null) return List.of();

        List<String> profiles = new ArrayList<>();
        for (String p : profileAnn.value().asStringArray()) {
            profiles.add(p);
        }
        return profiles;
    }

    private static List<String> mergeProfiles(List<String> classProfiles, List<String> methodProfiles) {
        Set<String> merged = new LinkedHashSet<>(classProfiles);
        merged.addAll(methodProfiles);
        return new ArrayList<>(merged);
    }

    private static List<String> extractClassBeanNames(ClassInfo ci, IndexView index) {
        Set<String> names = new LinkedHashSet<>();

        AnnotationInstance named = ci.declaredAnnotation(NAMED);
        if (named == null) named = ci.declaredAnnotation(NAMED_JAVAX);
        addStringValues(named == null ? null : named.value(), names);

        for (AnnotationInstance ann : ci.declaredAnnotations()) {
            if (isBeanStereotype(ann.name(), index, new HashSet<>())) {
                addStringValues(ann.value(), names);
            }
        }

        if (names.isEmpty()) {
            names.add(defaultBeanName(ci.name().withoutPackagePrefix()));
        }
        return new ArrayList<>(names);
    }

    private static List<String> extractBeanMethodNames(MethodInfo method) {
        Set<String> names = new LinkedHashSet<>();
        AnnotationInstance bean = method.annotation(BEAN_METHOD);
        if (bean != null) {
            addStringValues(bean.value("name"), names);
            addStringValues(bean.value(), names);
        }
        if (names.isEmpty()) names.add(method.name());
        return new ArrayList<>(names);
    }

    private static void addStringValues(AnnotationValue value, Set<String> target) {
        if (value == null) return;
        if (value.kind() == AnnotationValue.Kind.STRING) {
            if (!value.asString().isBlank()) target.add(value.asString());
        } else if (value.kind() == AnnotationValue.Kind.ARRAY) {
            for (String item : value.asStringArray()) {
                if (!item.isBlank()) target.add(item);
            }
        }
    }

    private static List<AnnotationInstance> methodLevelAnnotations(MethodInfo method) {
        return method.annotations().stream()
                .filter(ann -> ann.target() != null
                        && ann.target().kind() == AnnotationTarget.Kind.METHOD)
                .toList();
    }

    private static AnnotationInstance findAnnotationOrMeta(
            Collection<AnnotationInstance> annotations, DotName target,
            IndexView index) {
        for (AnnotationInstance ann : annotations) {
            if (ann.name().equals(target)) return ann;
        }
        for (AnnotationInstance ann : annotations) {
            AnnotationInstance found = findAnnotationOnAnnotationType(
                    ann.name(), target, index, new HashSet<>());
            if (found != null) return found;
        }
        return null;
    }

    private static AnnotationInstance findAnnotationOnAnnotationType(
            DotName annotationName, DotName target, IndexView index, Set<DotName> visited) {
        if (!visited.add(annotationName)) return null;
        ClassInfo annotationClass = index.getClassByName(annotationName);
        if (annotationClass == null) return null;

        AnnotationInstance direct = annotationClass.declaredAnnotation(target);
        if (direct != null) return direct;
        for (AnnotationInstance meta : annotationClass.declaredAnnotations()) {
            AnnotationInstance found = findAnnotationOnAnnotationType(
                    meta.name(), target, index, visited);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasMetaAnnotation(DotName annotationName, DotName target,
            IndexView index, Set<DotName> visited) {
        if (annotationName.equals(target)) return true;
        if (!visited.add(annotationName)) return false;
        ClassInfo annotationClass = index.getClassByName(annotationName);
        if (annotationClass == null) return false;
        for (AnnotationInstance meta : annotationClass.declaredAnnotations()) {
            if (hasMetaAnnotation(meta.name(), target, index, visited)) return true;
        }
        return false;
    }

    private static boolean isBeanStereotype(DotName annotationName, IndexView index,
            Set<DotName> visited) {
        if (BEAN_ANNOTATIONS.contains(annotationName)) return true;
        if (!visited.add(annotationName)) return false;
        ClassInfo annotationClass = index.getClassByName(annotationName);
        if (annotationClass == null) return false;
        for (AnnotationInstance meta : annotationClass.declaredAnnotations()) {
            if (isBeanStereotype(meta.name(), index, visited)) return true;
        }
        return false;
    }

    private static List<String> extractBeanTypes(ClassInfo ci, IndexView index) {
        Set<String> types = new LinkedHashSet<>();
        collectAllSupertypes(ci.name(), index, types);
        return new ArrayList<>(types);
    }

    private static List<String> buildProducerBeanTypes(MethodInfo method, IndexView index) {
        Set<String> types = new LinkedHashSet<>();
        collectAllSupertypes(method.returnType().name(), index, types);
        return new ArrayList<>(types);
    }

    static void collectAllSupertypes(DotName start, IndexView index, Set<String> result) {
        Deque<DotName> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            DotName current = queue.poll();
            if (current == null || "java.lang.Object".equals(current.toString())) continue;
            if (!result.add(current.toString())) continue;
            ClassInfo ci = index.getClassByName(current);
            if (ci == null) continue;
            queue.addAll(ci.interfaceNames());
            if (ci.superName() != null) {
                queue.add(ci.superName());
            }
        }
    }
}
