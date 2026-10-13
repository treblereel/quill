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
            EndpointHierarchy hierarchy = new EndpointHierarchy(owner, lookup);
            SpringSelection classSelection = scanner.springSelection(hierarchy, owner.name(), null, new HashSet<>());
            List<Mapping> classMappings = classSelection.mappings;
            List<String> springPaths = classMappings.size() == 1 ? classMappings.getFirst().paths : List.of();
            List<String> jaxPaths = owner.declaredAnnotations().stream()
                    .filter(a -> jax(a.name().toString(), "Path"))
                    .flatMap(a -> strings(a.value("value")).stream()).distinct().toList();
            for (MethodInfo method : hierarchy.effectiveMethods()) {
                EndpointHierarchy context = method.declaringClass().name().equals(owner.name())
                        ? hierarchy : new EndpointHierarchy(method.declaringClass(), lookup);
                SpringSelection selected = scanner.springSelection(context, method.declaringClass().name(),
                        context.key(method), new HashSet<>());
                if (!selected.blocked && !classSelection.blocked && selected.mappings.size() == 1 && classMappings.size() <= 1) {
                    Mapping mapping = selected.mappings.getFirst();
                    Set<String> combinedVerbs = new TreeSet<>(mapping.verbs);
                    if (classMappings.size() == 1) combinedVerbs.addAll(classMappings.getFirst().verbs);
                    List<String> verbs = List.copyOf(combinedVerbs);
                    List<String> sources = new ArrayList<>(classSelection.sources);
                    sources.addAll(selected.sources);
                    result.add(record(id, owner, method, "spring", verbs.isEmpty() ? List.of("ANY") : verbs,
                            springPaths, mapping.paths, mapping.evidence, ids, sources));
                }
                if (!java.lang.reflect.Modifier.isPublic(method.flags())) continue;
                MethodInfo jaxSource = scanner.jaxSource(hierarchy, method);
                if (jaxSource == null) continue;
                Set<String> verbs = new TreeSet<>();
                Set<String> paths = new LinkedHashSet<>();
                Set<String> evidence = new TreeSet<>();
                for (AnnotationInstance annotation : methodAnnotations(jaxSource)) {
                    String name = annotation.name().toString();
                    if (jax(name, "Path")) {paths.addAll(strings(annotation.value("value"))); evidence.add(name);}
                    String verb = scanner.jaxVerb(annotation);
                    if (verb != null) {verbs.add(verb); evidence.add(name);}
                }
                if (!evidence.isEmpty()) result.add(record(id, owner, method, "jax-rs",
                        verbs.isEmpty() ? List.of("SUBRESOURCE") : List.copyOf(verbs), jaxPaths,
                        paths.isEmpty() ? List.of("") : List.copyOf(paths), List.copyOf(evidence), ids,
                        jaxPaths.isEmpty() ? List.of(methodSource(jaxSource))
                                : List.of("class:" + owner.name(), methodSource(jaxSource))));
            }
        }
        return result;
    }

    private static FrameworkEndpointRecord record(int id, ClassInfo owner, MethodInfo method,
            String framework, List<String> verbs, List<String> classPaths,
            List<String> paths, List<String> evidence, Map<String, Integer> ids, List<String> sources) {
        String signature = method.name() + "(" + String.join(",", method.parameterTypes()
                .stream().map(Type::toString).toList()) + "):" + method.returnType();
        String declaring = method.declaringClass().name().toString();
        return new FrameworkEndpointRecord(id, owner.name().toString(), method.name(), signature,
                EndpointHierarchy.descriptor(method), framework, verbs, classPaths, paths, evidence,
                ids.get(declaring), declaring, sources.stream().distinct().toList());
    }

    private static List<AnnotationInstance> methodAnnotations(MethodInfo method) {
        return method.annotations().stream().filter(a -> a.target() != null
                && a.target().kind() == AnnotationTarget.Kind.METHOD).toList();
    }

    private static String methodSource(MethodInfo method) {
        return "method:" + method.declaringClass().name() + "#" + method.name() + EndpointHierarchy.descriptor(method);
    }

    private SpringSelection springSelection(EndpointHierarchy hierarchy, DotName type,
            String methodKey, Set<DotName> visited) {
        EndpointHierarchy.Node node = hierarchy.nodes.get(type);
        if (node == null || !visited.add(type)) return SpringSelection.EMPTY;
        MethodInfo method = methodKey == null ? null : hierarchy.matching(node, methodKey);
        List<AnnotationInstance> declared = methodKey == null ? node.type().declaredAnnotations()
                : method == null ? List.of() : methodAnnotations(method);
        List<Mapping> direct = spring(declared);
        if (!direct.isEmpty()) return new SpringSelection(direct,
                List.of(methodKey == null ? "class:" + type : methodSource(method)), false);
        if (declared.stream().anyMatch(a -> mappingDeclared(a, new HashSet<>())))
            return new SpringSelection(List.of(), List.of(), true);
        if (!hierarchy.complete) return methodKey == null
                ? new SpringSelection(List.of(), List.of(), true) : SpringSelection.EMPTY;
        List<SpringSelection> interfaces = new ArrayList<>();
        for (Type iface : node.type().interfaceTypes()) {
            SpringSelection selection = springSelection(hierarchy, iface.name(), methodKey, visited);
            if (!selection.mappings.isEmpty() || selection.blocked) interfaces.add(selection);
        }
        if (interfaces.size() == 1) return interfaces.getFirst();
        if (interfaces.size() > 1) return new SpringSelection(List.of(), List.of(), true);
        return node.type().superName() == null ? SpringSelection.EMPTY
                : springSelection(hierarchy, node.type().superName(), methodKey, visited);
    }

    private MethodInfo jaxSource(EndpointHierarchy hierarchy, MethodInfo method) {
        if (hasJaxAnnotations(method)) return method;
        if (!hierarchy.complete) return null;
        String key = hierarchy.key(method);
        for (EndpointHierarchy.Node node : hierarchy.nodes.values().stream()
                .filter(n -> !n.type().isInterface()).sorted(Comparator.comparingInt(EndpointHierarchy.Node::depth)).toList()) {
            MethodInfo candidate = hierarchy.matching(node, key);
            if (candidate != null && hasJaxAnnotations(candidate)) return candidate;
        }
        List<MethodInfo> candidates = new ArrayList<>();
        for (EndpointHierarchy.Node node : hierarchy.nodes.values()) if (node.type().isInterface()) {
            MethodInfo candidate = hierarchy.matching(node, key);
            if (candidate != null && hasJaxAnnotations(candidate)) candidates.add(candidate);
        }
        candidates = hierarchy.mostSpecific(candidates);
        // Conflicting interface contracts have implementation-specific precedence in JAX-RS.
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private boolean hasJaxAnnotations(MethodInfo method) {
        return method.annotations().stream().filter(a -> a.target() != null
                && (a.target().kind() == AnnotationTarget.Kind.METHOD
                    || a.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER))
                .anyMatch(a -> a.name().toString().startsWith("jakarta.ws.rs.")
                        || a.name().toString().startsWith("javax.ws.rs.") || customJaxDesignator(a));
    }

    private boolean customJaxDesignator(AnnotationInstance annotation) {
        ClassInfo definition = lookup.getClassByName(annotation.name());
        return definition != null && definition.isAnnotation() && definition.declaredAnnotations().stream()
                .anyMatch(a -> jax(a.name().toString(), "HttpMethod"));
    }

    private boolean mappingDeclared(AnnotationInstance annotation, Set<DotName> visited) {
        String name = annotation.name().toString();
        if (name.equals(REQUEST) || SPRING_VERBS.containsKey(name)) return true;
        if (visited.size() >= 128) return true; // Do not inherit past unresolved traversal evidence.
        if (!visited.add(annotation.name()) || name.startsWith("java.lang.annotation.")) return false;
        ClassInfo definition = lookup.getClassByName(annotation.name());
        return definition != null && definition.isAnnotation() && definition.declaredAnnotations().stream()
                .anyMatch(a -> mappingDeclared(a, visited));
    }

    private record SpringSelection(List<Mapping> mappings, List<String> sources, boolean blocked) {
        static final SpringSelection EMPTY = new SpringSelection(List.of(), List.of(), false);
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
