package org.treblereel.mcp.core;

import java.util.*;
import org.jboss.jandex.*;
import org.treblereel.mcp.model.FrameworkEndpointRecord;

/** Resolves constant, declared HTTP mappings without loading application classes. */
final class FrameworkEndpointScanner {
    private static final String SPRING = "org.springframework.web.bind.annotation.";
    private static final String REQUEST = SPRING + "RequestMapping";
    private static final String ALIAS = "org.springframework.core.annotation.AliasFor";
    private static final Map<String, String> SPRING_VERBS = Map.of(
            SPRING + "GetMapping", "GET", SPRING + "PostMapping", "POST",
            SPRING + "PutMapping", "PUT", SPRING + "DeleteMapping", "DELETE",
            SPRING + "PatchMapping", "PATCH");
    private static final Set<String> JAX_VERBS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private final IndexView lookup;

    private FrameworkEndpointScanner(IndexView lookup) { this.lookup = lookup; }

    static List<FrameworkEndpointRecord> extract(IndexView owners, IndexView lookup,
            Map<String, Integer> ids) {
        FrameworkEndpointScanner scanner = new FrameworkEndpointScanner(lookup);
        List<FrameworkEndpointRecord> result = new ArrayList<>();
        for (ClassInfo owner : owners.getKnownClasses().stream()
                .sorted(Comparator.comparing(c -> c.name().toString())).toList()) {
            Integer id = ids.get(owner.name().toString());
            if (id == null || owner.isAnnotation()) continue;
            List<Mapping> classMappings = scanner.spring(owner.declaredAnnotations());
            List<String> springPaths = classMappings.size() == 1
                    ? classMappings.getFirst().paths : List.of();
            List<String> jaxPaths = owner.declaredAnnotations().stream()
                    .filter(a -> jax(a.name().toString(), "Path"))
                    .flatMap(a -> strings(a.value("value")).stream()).distinct().toList();
            for (MethodInfo method : owner.methods()) {
                if (method.name().startsWith("<") || (method.flags() & 0x1000) != 0) continue;
                List<AnnotationInstance> annotations = method.annotations().stream()
                        .filter(a -> a.target() != null
                                && a.target().kind() == AnnotationTarget.Kind.METHOD).toList();
                List<Mapping> spring = scanner.spring(annotations);
                // Multiple mappings on one element have framework-dependent precedence.
                if (spring.size() == 1 && classMappings.size() <= 1) {
                    Mapping mapping = spring.getFirst();
                    Set<String> combinedVerbs = new TreeSet<>(mapping.verbs);
                    if (classMappings.size() == 1) combinedVerbs.addAll(classMappings.getFirst().verbs);
                    List<String> verbs = List.copyOf(combinedVerbs);
                    result.add(record(id, owner, method, "spring", verbs.isEmpty()
                            ? List.of("ANY") : verbs, springPaths, mapping.paths, mapping.evidence));
                }
                Set<String> verbs = new TreeSet<>();
                Set<String> paths = new LinkedHashSet<>();
                Set<String> evidence = new TreeSet<>();
                for (AnnotationInstance annotation : annotations) {
                    String name = annotation.name().toString();
                    if (jax(name, "Path")) {
                        paths.addAll(strings(annotation.value("value"))); evidence.add(name);
                    }
                    String verb = scanner.jaxVerb(annotation);
                    if (verb != null) { verbs.add(verb); evidence.add(name); }
                }
                if (!evidence.isEmpty()) result.add(record(id, owner, method, "jax-rs",
                        verbs.isEmpty() ? List.of("SUBRESOURCE") : List.copyOf(verbs),
                        jaxPaths, paths.isEmpty() ? List.of("") : List.copyOf(paths),
                        List.copyOf(evidence)));
            }
        }
        return result;
    }

    private static FrameworkEndpointRecord record(int id, ClassInfo owner, MethodInfo method,
            String framework, List<String> verbs, List<String> classPaths,
            List<String> paths, List<String> evidence) {
        String signature = method.name() + "(" + String.join(",", method.parameterTypes()
                .stream().map(Type::toString).toList()) + "):" + method.returnType();
        return new FrameworkEndpointRecord(id, owner.name().toString(), method.name(), signature,
                method.descriptor(identifier -> java.util.stream.Stream.concat(
                        method.typeParameters().stream(), owner.typeParameters().stream())
                        .filter(v -> v.identifier().equals(identifier)).findFirst()
                        .flatMap(v -> v.bounds().stream().findFirst())
                        .orElseGet(() -> Type.create(Object.class))),
                framework, verbs, classPaths, paths, evidence);
    }

    private String jaxVerb(AnnotationInstance annotation) {
        String name = annotation.name().toString();
        for (String verb : JAX_VERBS) if (jax(name, verb)) return verb;
        ClassInfo definition = lookup.getClassByName(annotation.name());
        if (definition == null || !definition.isAnnotation()) return null;
        // JAX-RS uses one direct HttpMethod meta-annotation, not arbitrary meta chains.
        List<AnnotationInstance> designators = definition.declaredAnnotations().stream()
                .filter(a -> jax(a.name().toString(), "HttpMethod")).toList();
        if (designators.size() != 1) return null;
        List<String> value = strings(designators.getFirst().value("value"));
        if (value.size() != 1 || !value.getFirst().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) return null;
        return value.getFirst();
    }

    private static boolean jax(String name, String simple) {
        return name.equals("jakarta.ws.rs." + simple) || name.equals("javax.ws.rs." + simple);
    }

    private List<Mapping> spring(Collection<AnnotationInstance> annotations) {
        List<Mapping> result = new ArrayList<>();
        for (AnnotationInstance annotation : annotations) {
            int[] budget = {512};
            List<Mapping> resolved = spring(annotation, Map.of(), new LinkedHashSet<>(), budget);
            if (budget[0] >= 0) result.addAll(resolved);
        }
        return result.stream().distinct().toList();
    }

    private List<Mapping> spring(AnnotationInstance annotation,
            Map<String, Map<String, AnnotationValue>> overrides, LinkedHashSet<String> chain, int[] budget) {
        String name = annotation.name().toString();
        if (--budget[0] < 0 || chain.size() >= 32 || !chain.add(name)) return List.of();
        try {
            Map<String, AnnotationValue> values = new HashMap<>();
            ClassInfo definition = lookup.getClassByName(annotation.name());
            if (definition != null && definition.isAnnotation()) {
                for (MethodInfo attribute : definition.methods()) {
                    AnnotationValue value = annotation.valueWithDefault(lookup, attribute.name());
                    if (value != null) values.put(attribute.name(), value);
                }
            }
            annotation.values().forEach(v -> values.put(v.name(), v));
            values.putAll(overrides.getOrDefault(name, Map.of()));
            if (name.equals(REQUEST) || SPRING_VERBS.containsKey(name)) {
                Map<String, AnnotationValue> rootOverrides = new HashMap<>(overrides.getOrDefault(REQUEST, Map.of()));
                rootOverrides.putAll(overrides.getOrDefault(name, Map.of()));
                values.putAll(rootOverrides);
                if (rootOverrides.containsKey("path") && !rootOverrides.containsKey("value"))
                    values.put("value", rootOverrides.get("path"));
                if (rootOverrides.containsKey("value") && !rootOverrides.containsKey("path"))
                    values.put("path", rootOverrides.get("value"));
                List<String> path = strings(values.get("path"));
                List<String> value = strings(values.get("value"));
                if (!path.isEmpty() && !value.isEmpty() && !path.equals(value)) return List.of();
                List<String> paths = !path.isEmpty() ? path : value;
                List<String> verbs = name.equals(REQUEST) || rootOverrides.containsKey("method")
                        ? enums(values.get("method")) : List.of(SPRING_VERBS.get(name));
                return List.of(new Mapping(verbs, paths.isEmpty() ? List.of("") : paths,
                        List.copyOf(chain)));
            }
            if (definition == null || !definition.isAnnotation()) return List.of();
            // Resolve local aliases first, independent of declaration order.
            for (MethodInfo attribute : definition.methods()) {
                AnnotationInstance alias = attribute.annotation(DotName.createSimple(ALIAS));
                if (alias == null) continue;
                AnnotationValue target = alias.value("annotation");
                if (target != null && !target.asClass().name().toString().equals(name)
                        && !target.asClass().name().toString().equals("java.lang.annotation.Annotation")) continue;
                List<String> attr = strings(alias.value("attribute"));
                if (attr.isEmpty() || attr.getFirst().isEmpty()) attr = strings(alias.value("value"));
                if (attr.isEmpty() || attr.getFirst().isEmpty()) continue;
                String other = attr.getFirst();
                MethodInfo otherMethod = definition.methods().stream()
                        .filter(m -> m.name().equals(other)).findFirst().orElse(null);
                if (otherMethod == null) return List.of();
                AnnotationInstance otherAlias = otherMethod.annotation(DotName.createSimple(ALIAS));
                AnnotationValue otherTarget = otherAlias == null ? null : otherAlias.value("annotation");
                if (otherTarget != null && !otherTarget.asClass().name().toString().equals(name)
                        && !otherTarget.asClass().name().toString().equals("java.lang.annotation.Annotation"))
                    return List.of();
                AnnotationValue supplied = overrides.getOrDefault(name, Map.of()).get(attribute.name());
                if (supplied == null) supplied = annotation.value(attribute.name());
                if (supplied == null) continue;
                AnnotationValue otherSupplied = annotation.value(other);
                if (otherSupplied != null && !strings(supplied).equals(strings(otherSupplied))) return List.of();
                values.put(other, supplied);
            }
            Map<String, Map<String, AnnotationValue>> next = new HashMap<>();
            overrides.forEach((key, val) -> next.put(key, new HashMap<>(val)));
            for (MethodInfo attribute : definition.methods()) {
                AnnotationInstance alias = attribute.annotation(DotName.createSimple(ALIAS));
                if (alias == null) continue;
                String target = name;
                AnnotationValue targetValue = alias.value("annotation");
                if (targetValue != null) target = targetValue.asClass().name().toString();
                if (target.equals("java.lang.annotation.Annotation")) target = name;
                List<String> attr = strings(alias.value("attribute"));
                if (attr.isEmpty() || attr.getFirst().isEmpty()) attr = strings(alias.value("value"));
                String targetAttribute = attr.isEmpty() || attr.getFirst().isEmpty()
                        ? attribute.name() : attr.getFirst();
                AnnotationValue v = values.get(attribute.name());
                if (target.equals(name)) continue;
                if (v != null) {
                    Map<String, AnnotationValue> targetValues = next.computeIfAbsent(
                            target, ignored -> new HashMap<>());
                    if (overrides.getOrDefault(target, Map.of()).containsKey(targetAttribute)) continue;
                    AnnotationValue previous = targetValues.putIfAbsent(targetAttribute, v);
                    if (previous != null && (!strings(previous).equals(strings(v))
                            || !enums(previous).equals(enums(v)))) {
                        boolean previousEmpty = strings(previous).isEmpty() && enums(previous).isEmpty();
                        boolean currentEmpty = strings(v).isEmpty() && enums(v).isEmpty();
                        if (previousEmpty) targetValues.put(targetAttribute, v);
                        else if (!currentEmpty) return List.of();
                    }
                }
            }
            List<Mapping> result = new ArrayList<>();
            for (AnnotationInstance meta : definition.declaredAnnotations()) {
                if (!meta.name().toString().startsWith("java.lang.annotation."))
                    result.addAll(spring(meta, next, chain, budget));
            }
            return result;
        } finally { chain.remove(name); }
    }

    private static List<String> strings(AnnotationValue value) {
        if (value == null) return List.of();
        if (value.kind() == AnnotationValue.Kind.STRING) return List.of(value.asString());
        if (value.kind() != AnnotationValue.Kind.ARRAY) return List.of();
        try { return Arrays.asList(value.asStringArray()); }
        catch (IllegalArgumentException ignored) { return List.of(); }
    }

    private static List<String> enums(AnnotationValue value) {
        if (value == null) return List.of();
        if (value.kind() == AnnotationValue.Kind.ENUM) return List.of(value.asEnum());
        if (value.kind() != AnnotationValue.Kind.ARRAY) return List.of();
        try { return Arrays.asList(value.asEnumArray()); }
        catch (IllegalArgumentException ignored) { return List.of(); }
    }

    private record Mapping(List<String> verbs, List<String> paths, List<String> evidence) {}
}
