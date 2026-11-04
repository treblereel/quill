package org.treblereel.mcp.core;

import java.lang.reflect.Modifier;
import java.util.*;

import org.jboss.jandex.*;
import org.treblereel.mcp.model.*;

/**
 * Extracts a CDI bean graph directly from Jandex, without starting a CDI container.
 */
public final class BeanResolver {
    private BeanResolver() {
    }

    public record ResolutionResult(List<BeanRecord> beans, List<InjectionPointRecord> injectionPoints,
                                   List<DependencyRecord> dependencies, Map<String, Integer> classNameToId,
                                   List<CdiProblem> problems) {
    }

    private static DotName n(String value) {
        return DotName.createSimple(value);
    }

    private static final DotName INJECT = n("jakarta.inject.Inject"), QUALIFIER = n("jakarta.inject.Qualifier"),
            NAMED = n("jakarta.inject.Named"), SCOPE = n("jakarta.inject.Scope"),
            NORMAL_SCOPE = n("jakarta.enterprise.context.NormalScope"),
            STEREOTYPE = n("jakarta.enterprise.inject.Stereotype"),
            PRODUCES = n("jakarta.enterprise.inject.Produces"),
            SPECIALIZES = n("jakarta.enterprise.inject.Specializes"),
            ALTERNATIVE = n("jakarta.enterprise.inject.Alternative"), PRIORITY = n("jakarta.annotation.Priority"),
            INTERCEPTOR = n("jakarta.interceptor.Interceptor"), DECORATOR = n("jakarta.decorator.Decorator"),
            DEFAULT_BEAN = n("io.quarkus.arc.DefaultBean"),
            IF_PROFILE = n("io.quarkus.arc.profile.IfBuildProfile"),
            UNLESS_PROFILE = n("io.quarkus.arc.profile.UnlessBuildProfile"),
            CONFIG_MAPPING = n("io.smallrye.config.ConfigMapping"),
            CONFIG_ROOT = n("io.quarkus.runtime.annotations.ConfigRoot"),
            REGISTER_REST_CLIENT = n("org.eclipse.microprofile.rest.client.inject.RegisterRestClient");
    /** Provenance recorded as the member of synthetic REST client beans. */
    static final String REST_CLIENT_CREATOR = "synthetic:RestClient";
    /** Provenance recorded as the member of synthetic config-mapping beans. */
    static final String CONFIG_MAPPING_CREATOR = "synthetic:ConfigMappingCreator";
    private static final Set<DotName> SCOPES = Set.of(n("jakarta.enterprise.context.ApplicationScoped"),
            n("jakarta.enterprise.context.RequestScoped"), n("jakarta.enterprise.context.SessionScoped"),
            n("jakarta.enterprise.context.ConversationScoped"), n("jakarta.enterprise.context.Dependent"),
            n("jakarta.inject.Singleton"));
    private static final Set<DotName> CDI_SIGNALS = Set.of(n("jakarta.enterprise.context.ApplicationScoped"),
            n("jakarta.enterprise.context.RequestScoped"), n("jakarta.enterprise.context.SessionScoped"),
            n("jakarta.enterprise.context.ConversationScoped"), n("jakarta.enterprise.context.Dependent"),
            PRODUCES, STEREOTYPE, INTERCEPTOR, DECORATOR);

    public static boolean isCdiProject(IndexView index) {
        for (DotName signal : CDI_SIGNALS) if (!index.getAnnotations(signal).isEmpty()) return true;
        return index.getKnownClasses().stream().anyMatch(c -> c.isAnnotation()
                && (c.hasDeclaredAnnotation(SCOPE) || c.hasDeclaredAnnotation(NORMAL_SCOPE)));
    }

    public static ResolutionResult resolve(Index applicationIndex) {
        return resolve(applicationIndex, (IndexView) null);
    }

    public static ResolutionResult resolve(Index applicationIndex, Index dependencyIndex) {
        return resolve(applicationIndex, (IndexView) dependencyIndex);
    }

    public static ResolutionResult resolve(Index applicationIndex, IndexView dependencyIndex) {
        return resolve((IndexView) applicationIndex, dependencyIndex);
    }

    public static ResolutionResult resolve(IndexView applicationIndex, IndexView dependencyIndex) {
        return resolve(applicationIndex, dependencyIndex, Map.of());
    }

    public static ResolutionResult resolve(IndexView applicationIndex, IndexView dependencyIndex,
            Map<String, String> frameworkScopes) {
        IndexView lookup = dependencyIndex == null ? applicationIndex
                : CompositeIndex.create(applicationIndex, dependencyIndex);
        List<BeanRecord> beans = new ArrayList<>();
        List<InjectionPointRecord> ips = new ArrayList<>();
        List<DependencyRecord> deps = new ArrayList<>();
        Map<String, Integer> classIds = new LinkedHashMap<>();
        Map<DotName, Integer> classBeanIds = new HashMap<>();
        Map<MethodInfo, Integer> producerIds = new HashMap<>();
        List<ClassInfo> classes = applicationIndex.getKnownClasses().stream()
                .sorted(Comparator.comparing(c -> c.name().toString())).toList();
        int nextBean = 1;
        for (ClassInfo c : classes) {
            boolean declared = isBeanClass(c, lookup);
            String frameworkScope = frameworkScopes.get(c.name().toString());
            if (!declared && (frameworkScope == null || !frameworkBeanClass(c))) continue;
            int classId = classIds.computeIfAbsent(c.name().toString(), x -> classIds.size() + 1);
            int ownerId = nextBean++;
            classBeanIds.put(c.name(), ownerId);
            beans.add(new BeanRecord(ownerId, classId, beanKind(c), declared ? scope(c.declaredAnnotations(), lookup) : inheritedScope(c, lookup, frameworkScope),
                    qualifiers(c.declaredAnnotations(), lookup, true), stereotypes(c, lookup),
                    hasMeta(c.declaredAnnotations(), ALTERNATIVE, lookup),
                    hasAnnotation(c.declaredAnnotations(), DEFAULT_BEAN),
                    priority(c.declaredAnnotations(), lookup),
                    profiles(c), null, null, beanTypes(Type.create(c.name(), Type.Kind.CLASS), c.declaredAnnotations(), lookup)));
            for (FieldInfo f : c.fields())
                if (f.hasAnnotation(PRODUCES)) {
                    beans.add(new BeanRecord(nextBean++, classId, "PRODUCER_FIELD", scope(f.annotations(), lookup),
                            qualifiers(f.annotations(), lookup, true), List.of(),
                            hasAnnotation(f.annotations(), ALTERNATIVE) || isAlternative(c, lookup),
                            hasAnnotation(f.annotations(), DEFAULT_BEAN),
                            first(priority(f.annotations(), lookup), priority(c.declaredAnnotations(), lookup)),
                            profiles(c, f.annotations()), classId, f.name(), beanTypes(f.type(), f.annotations(), lookup)));
                }
            for (MethodInfo m : c.methods())
                if (producerMethod(m, lookup, frameworkScopes.containsKey(c.name().toString()))) {
                    int producerId = nextBean++;
                    producerIds.put(m, producerId);
                    Collection<AnnotationInstance> annotations = methodAnnotations(m);
                    beans.add(new BeanRecord(producerId, classId, "PRODUCER_METHOD", scope(annotations, lookup),
                            qualifiers(annotations, lookup, true), List.of(),
                            hasAnnotation(annotations, ALTERNATIVE) || isAlternative(c, lookup),
                            hasAnnotation(annotations, DEFAULT_BEAN),
                            first(priority(annotations, lookup), priority(c.declaredAnnotations(), lookup)),
                            profiles(c, annotations), classId, m.name(), beanTypes(m.returnType(), annotations, lookup)));
                }
        }
        for (ClassInfo c : classes) {
            // SmallRye Config registers each @ConfigMapping interface as a synthetic bean (Quarkus
            // ConfigBuildStep, or the SmallRye ConfigExtension elsewhere). No scope is configured,
            // so the bean is @Dependent with @Default/@Any; the creator, not a bean class, builds it.
            if (!c.isInterface() || !c.hasDeclaredAnnotation(CONFIG_MAPPING) || buildTimeConfigRoot(c)) continue;
            if (lookup.getClassByName(n("io.quarkus.arc.runtime.ConfigMappingCreator")) == null
                    && lookup.getClassByName(n("io.smallrye.config.inject.ConfigExtension")) == null) continue;
            int classId = classIds.computeIfAbsent(c.name().toString(), x -> classIds.size() + 1);
            beans.add(new BeanRecord(nextBean++, classId, "SYNTHETIC", "@Dependent",
                    qualifiers(List.of(), lookup, true), List.of(), false, false, null,
                    profiles(c), null, CONFIG_MAPPING_CREATOR,
                    beanTypes(Type.create(c.name(), Type.Kind.CLASS), List.of(), lookup)));
        }
        for (ClassInfo c : classes) {
            // Quarkus registers each @RegisterRestClient interface as a synthetic bean injected
            // with @RestClient; its scope is the interface's scope annotation or @ApplicationScoped
            // (quarkus.rest-client."config-key".scope can change it at build time).
            if (!c.isInterface() || !c.hasDeclaredAnnotation(REGISTER_REST_CLIENT)) continue;
            int classId = classIds.computeIfAbsent(c.name().toString(), x -> classIds.size() + 1);
            String declaredScope = hasScope(c.declaredAnnotations(), lookup)
                    ? scope(c.declaredAnnotations(), lookup) : "@ApplicationScoped";
            beans.add(new BeanRecord(nextBean++, classId, "SYNTHETIC", declaredScope,
                    List.of("@RestClient", BuiltinQualifier.ANY.annotation()), List.of(), false, false, null,
                    profiles(c), null, REST_CLIENT_CREATOR,
                    beanTypes(Type.create(c.name(), Type.Kind.CLASS), List.of(), lookup)));
        }
        int nextIp = 1;
        for (ClassInfo c : classes) {
            Integer ownerId = classBeanIds.get(c.name());
            if (ownerId == null) continue;
            for (FieldInfo f : c.fields())
                if (f.hasAnnotation(INJECT)) {
                    ips.add(ip(nextIp++, ownerId, "FIELD", f.type(), qualifiers(f.annotations(), lookup, false), f.name(), f.hasAnnotation(n("jakarta.decorator.Delegate"))));
                }
            for (MethodInfo m : c.methods()) {
                boolean injected = (m.flags() & (0x0040 | 0x1000)) == 0 && methodHas(m, INJECT), producer = producerMethod(m, lookup, frameworkScopes.containsKey(c.name().toString()));
                if (!injected && !producer) continue;
                int parameterOwner = producer ? producerIds.getOrDefault(m, ownerId) : ownerId;
                String kind = "<init>".equals(m.name()) ? "CONSTRUCTOR_PARAM" : "METHOD_PARAM";
                for (int p = 0; p < m.parametersCount(); p++)
                    ips.add(ip(nextIp++, parameterOwner, kind, m.parameterType(p),
                            qualifiers(parameterAnnotations(m, p), lookup, false), m.name(),
                            parameterAnnotations(m, p).stream().anyMatch(a -> a.name().equals(n("jakarta.decorator.Delegate")))));
            }
            nextIp = inheritedInjectionPoints(c, ownerId, lookup, ips, nextIp);
        }
        Map<Integer, Integer> specializedBeans = specializedBeans(classes, classBeanIds);
        inheritSpecializedQualifiers(beans, specializedBeans);
        Map<Integer, String> classNames = new HashMap<>();
        classIds.forEach((name, id) -> classNames.put(id, name));
        reResolveUnresolved(beans, ips, deps, specializedBeans, classNames);
        ips.replaceAll(ip -> ip.resolvedBeanId() == null ? ip.asCdiBuiltin() : ip);
        addNonBeanDependencies(applicationIndex, beans, deps, classIds);
        return new ResolutionResult(beans, ips, deps, classIds, List.of());
    }

    /**
     * Quarkus extension config roots whose phase is BUILD_TIME (the @ConfigRoot default) exist only
     * while the application is built; they are not registered as runtime beans.
     */
    private static boolean buildTimeConfigRoot(ClassInfo c) {
        AnnotationInstance root = c.declaredAnnotation(CONFIG_ROOT);
        if (root == null) return false;
        AnnotationValue phase = root.value("phase");
        return phase == null || "BUILD_TIME".equals(phase.asEnum());
    }

    /**
     * CDI injects the @Inject fields and initializer methods declared by every superclass of a
     * bean class (CDI 4.1, 4.2), including superclasses from dependencies. Initializers
     * overridden lower in the hierarchy are not called through the superclass declaration.
     */
    private static int inheritedInjectionPoints(ClassInfo bean, int ownerId, IndexView lookup,
            List<InjectionPointRecord> ips, int nextIp) {
        List<ClassInfo> superclasses = InjectionHierarchy.superclasses(bean, lookup);
        for (ClassInfo parent : superclasses) {
            for (FieldInfo f : parent.fields())
                if (f.hasAnnotation(INJECT) && !Modifier.isStatic(f.flags())) {
                    ips.add(ip(nextIp++, ownerId, "FIELD", f.type(), qualifiers(f.annotations(), lookup, false),
                            InjectionHierarchy.inheritedMember(parent, f.name())));
                }
            List<ClassInfo> lower = InjectionHierarchy.below(bean, superclasses, parent);
            for (MethodInfo m : parent.methods()) {
                if ("<init>".equals(m.name()) || Modifier.isStatic(m.flags())) continue;
                if (!methodHas(m, INJECT) || methodHas(m, PRODUCES)
                        || InjectionHierarchy.overridden(m, lower)) continue;
                for (int p = 0; p < m.parametersCount(); p++)
                    ips.add(ip(nextIp++, ownerId, "METHOD_PARAM", m.parameterType(p),
                            qualifiers(parameterAnnotations(m, p), lookup, false),
                            InjectionHierarchy.inheritedMember(parent, m.name())));
            }
        }
        return nextIp;
    }

    private static InjectionPointRecord ip(int id, int owner, String kind, Type type, List<String> qs, String member) {
        return ip(id, owner, kind, type, qs, member, false);
    }

    private static InjectionPointRecord ip(int id, int owner, String kind, Type type, List<String> qs, String member, boolean delegate) {
        if (delegate) return new InjectionPointRecord(id, owner, kind, typeName(type), qs, member,
                null, ResolutionStatus.UNSUPPORTED_MECHANISM, InjectionPointRecord.STATIC_CDI,
                ResolutionReason.DECORATOR_DELEGATE_CHAIN, ResolutionConfidence.LOW,
                List.of("Delegate injection follows the enabled decorator chain, not ordinary bean selection."),
                new ResolutionTrace(List.of(), List.of(), List.of(ResolutionRule.DECORATOR_DELEGATE_CHAIN)));
        return InjectionPointRecord.staticAnalysis(id, owner, kind, typeName(type), qs,
                member, null, false, InjectionPointRecord.STATIC_CDI);
    }

    static boolean frameworkBeanClass(ClassInfo c) {
        return !c.isAnnotation() && !c.isInterface() && !Modifier.isAbstract(c.flags())
                && (c.enclosingClass() == null || Modifier.isStatic(c.flags())) && c.enclosingMethod() == null
                && !c.hasDeclaredAnnotation(n("jakarta.enterprise.inject.Vetoed"));
    }

    static boolean vetoed(ClassInfo c, IndexView index) {
        ClassInfo pkg = index.getClassByName(n(c.name().packagePrefix() + ".package-info"));
        return c.hasDeclaredAnnotation(n("jakarta.enterprise.inject.Vetoed"))
                || pkg != null && pkg.hasDeclaredAnnotation(n("jakarta.enterprise.inject.Vetoed"));
    }

    public static boolean quarkusProducerClass(ClassInfo c, IndexView index) {
        return frameworkBeanClass(c) && !vetoed(c, index)
                && (c.fields().stream().anyMatch(f -> f.hasAnnotation(PRODUCES))
                    || c.methods().stream().anyMatch(m -> producerMethod(m, index, true)));
    }

    private static boolean producerMethod(MethodInfo method, IndexView index, boolean quarkus) {
        if (method.isConstructor() || (method.flags() & (0x0040 | 0x1000)) != 0 || method.returnType().kind() == Type.Kind.VOID) return false;
        var annotations = methodAnnotations(method);
        return methodHas(method, PRODUCES) || quarkus && (hasScope(annotations, index)
                || hasMeta(annotations, STEREOTYPE, index) || hasMeta(annotations, QUALIFIER, index));
    }

    private static boolean isBeanClass(ClassInfo c, IndexView index) {
        if (c.isAnnotation() || c.isInterface() || Modifier.isAbstract(c.flags()) && !c.hasDeclaredAnnotation(DECORATOR) || vetoed(c, index)) return false;
        return hasScope(c.declaredAnnotations(), index) || hasMeta(c.declaredAnnotations(), STEREOTYPE, index)
                || c.hasDeclaredAnnotation(INTERCEPTOR) || c.hasDeclaredAnnotation(DECORATOR);
    }

    private static String beanKind(ClassInfo c) {
        if (c.hasDeclaredAnnotation(INTERCEPTOR)) return "INTERCEPTOR";
        if (c.hasDeclaredAnnotation(DECORATOR)) return "DECORATOR";
        return "CLASS";
    }

    private static boolean isAlternative(ClassInfo c, IndexView index) {
        return hasMeta(c.declaredAnnotations(), ALTERNATIVE, index);
    }

    private static boolean hasScope(Collection<AnnotationInstance> annotations, IndexView index) {
        for (var a : annotations)
            if (SCOPES.contains(a.name()) || metaHas(a.name(), SCOPE, index, new HashSet<>())
                    || metaHas(a.name(), NORMAL_SCOPE, index, new HashSet<>())) return true;
        return false;
    }

    private static String scope(Collection<AnnotationInstance> annotations, IndexView index) {
        for (var a : annotations) {
            if (SCOPES.contains(a.name())) return simple(a.name());
            ClassInfo annotationClass = index.getClassByName(a.name());
            if (annotationClass != null && (annotationClass.hasDeclaredAnnotation(SCOPE)
                    || annotationClass.hasDeclaredAnnotation(NORMAL_SCOPE))) return simple(a.name());
        }
        for (var a : annotations) {
            ClassInfo ac = index.getClassByName(a.name());
            if (ac != null && ac.hasDeclaredAnnotation(STEREOTYPE)) {
                String inherited = scope(ac.declaredAnnotations(), index);
                if (!"@Dependent".equals(inherited)) return inherited;
            }
        }
        return "@Dependent";
    }

    private static String inheritedScope(ClassInfo c, IndexView index, String fallback) {
        Set<DotName> seen = new HashSet<>();
        while (c.superName() != null && seen.add(c.name())) {
            c = index.getClassByName(c.superName());
            if (c == null) break;
            if (hasScope(c.declaredAnnotations(), index)) {
                var inherited = c.declaredAnnotations().stream().filter(a -> {
                    ClassInfo annotation = index.getClassByName(a.name());
                    return annotation != null && annotation.hasDeclaredAnnotation(n("java.lang.annotation.Inherited"));
                }).toList();
                return hasScope(inherited, index) ? scope(inherited, index) : fallback;
            }
        }
        return fallback;
    }

    private static List<String> stereotypes(ClassInfo c, IndexView index) {
        return c.declaredAnnotations().stream().filter(a -> metaHas(a.name(), STEREOTYPE, index, new HashSet<>()))
                .map(a -> simple(a.name())).toList();
    }

    private static List<String> qualifiers(Collection<AnnotationInstance> annotations, IndexView index, boolean bean) {
        List<String> result = new ArrayList<>();
        for (var a : annotations) {
            if (a.name().equals(NAMED)) {
                String value = a.value() == null ? "" : a.value().asString();
                result.add(value.isEmpty() ? BuiltinQualifier.NAMED.annotation() : "@Named(\"" + value + "\")");
            } else if (metaHas(a.name(), QUALIFIER, index, new HashSet<>())) result.add(qualifierIdentity(a, index));
        }
        if (bean) {
            boolean custom = result.stream().anyMatch(q -> !q.equals(BuiltinQualifier.ANY.annotation()) && !q.equals(BuiltinQualifier.DEFAULT.annotation()) && !q.startsWith(BuiltinQualifier.NAMED.annotation()));
            if (!custom && !result.contains(BuiltinQualifier.DEFAULT.annotation())) result.add(BuiltinQualifier.DEFAULT.annotation());
            if (!result.contains(BuiltinQualifier.ANY.annotation())) result.add(BuiltinQualifier.ANY.annotation());
        } else if (result.isEmpty() || result.stream().allMatch(q -> q.startsWith(BuiltinQualifier.NAMED.annotation())))
            result.add(BuiltinQualifier.DEFAULT.annotation());
        return List.copyOf(result);
    }

    private static boolean hasMeta(Collection<AnnotationInstance> annotations, DotName wanted, IndexView index) {
        if (hasAnnotation(annotations, wanted)) return true;
        for (var a : annotations) if (metaHas(a.name(), wanted, index, new HashSet<>())) return true;
        return false;
    }

    private static boolean metaHas(DotName annotation, DotName wanted, IndexView index, Set<DotName> visited) {
        if (!visited.add(annotation)) return false;
        ClassInfo c = index.getClassByName(annotation);
        if (c == null) return false;
        if (c.hasDeclaredAnnotation(wanted)) return true;
        for (var a : c.declaredAnnotations())
            if (a.name().equals(wanted) || metaHas(a.name(), wanted, index, visited)) return true;
        return false;
    }

    private static boolean hasAnnotation(Collection<AnnotationInstance> annotations, DotName wanted) {
        return annotations.stream().anyMatch(a -> a.name().equals(wanted));
    }

    private static Integer priority(Collection<AnnotationInstance> annotations, IndexView index) {
        for (var a : annotations) if (a.name().equals(PRIORITY) && a.value() != null) return a.value().asInt();
        for (var a : annotations) {
            ClassInfo c = index.getClassByName(a.name());
            AnnotationInstance p = c == null ? null : c.declaredAnnotation(PRIORITY);
            if (p != null && p.value() != null) return p.value().asInt();
        }
        return null;
    }

    private static <T> T first(T a, T b) {
        return a != null ? a : b;
    }

    private static String qualifierIdentity(AnnotationInstance annotation, IndexView index) {
        if (annotation.name().toString().equals("jakarta.enterprise.inject.Any")) return "@Any";
        if (annotation.name().toString().equals("jakarta.enterprise.inject.Default")) return "@Default";
        ClassInfo declaration = index.getClassByName(annotation.name());
        Map<String, String> values = new TreeMap<>();
        if (declaration != null) for (MethodInfo member : declaration.methods()) {
            if (member.hasAnnotation(n("jakarta.enterprise.util.Nonbinding"))) continue;
            AnnotationValue value = annotation.value(member.name());
            if (value == null) value = member.defaultValue();
            if (value != null) values.put(member.name(), value.toString());
        }
        // Include qualified identity so equally named qualifiers in different packages never alias.
        return "@" + annotation.name() + (values.isEmpty() ? "" : "(" + String.join(",", values.values()) + ")");
    }

    private static List<String> beanTypes(Type type, Collection<AnnotationInstance> annotations, IndexView index) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        addType(type, index, types, new HashSet<>());
        types.add("java.lang.Object");
        for (var annotation : annotations) if (annotation.name().equals(n("jakarta.enterprise.inject.Typed"))) {
            Set<String> allowed = new HashSet<>();
            if (annotation.value() != null) for (Type t : annotation.value().asClassArray()) allowed.add(typeName(t));
            types.removeIf(t -> !t.equals("java.lang.Object") && !allowed.contains(t.contains("<") ? t.substring(0,t.indexOf('<')) : t));
        }
        return List.copyOf(types);
    }

    private static void addType(Type type, IndexView index, Set<String> result, Set<DotName> visited) {
        if (type.kind() == Type.Kind.ARRAY || type.kind() == Type.Kind.PRIMITIVE) {
            result.add(typeName(type)); return;
        }
        DotName name = type.name();
        if (name == null || !visited.add(name)) return;
        result.add(type.kind() == Type.Kind.PARAMETERIZED_TYPE ? type.toString() : name.toString());
        ClassInfo c = index.getClassByName(name);
        if (c == null) return;
        for (Type i : c.interfaceTypes()) addType(i, index, result, visited);
        if (c.superClassType() != null) addType(c.superClassType(), index, result, visited);
    }

    static String assignableType(String type) {
        return switch (type) {
            case "boolean" -> "java.lang.Boolean"; case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short"; case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long"; case "char" -> "java.lang.Character";
            case "float" -> "java.lang.Float"; case "double" -> "java.lang.Double";
            default -> type;
        };
    }

    static boolean hasBeanType(BeanRecord bean, String target) {
        return bean.beanTypes() != null && bean.beanTypes().stream()
                .anyMatch(type -> assignableType(type).equals(assignableType(target)));
    }

    private static String typeName(Type type) {
        return type.kind() == Type.Kind.ARRAY || type.kind() == Type.Kind.PRIMITIVE || type.kind() == Type.Kind.PARAMETERIZED_TYPE
                ? type.toString() : type.name() == null ? type.toString() : type.name().toString();
    }

    private static boolean methodHas(MethodInfo m, DotName wanted) {
        return methodAnnotations(m).stream().anyMatch(a -> a.name().equals(wanted));
    }

    private static List<AnnotationInstance> methodAnnotations(MethodInfo m) {
        return m.annotations().stream().filter(a -> a.target() != null && a.target().kind() == AnnotationTarget.Kind.METHOD).toList();
    }

    private static List<AnnotationInstance> parameterAnnotations(MethodInfo m, int p) {
        return m.annotations().stream().filter(a -> a.target() != null && a.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER && a.target().asMethodParameter().position() == p).toList();
    }

    private static List<String> profiles(ClassInfo c) { return profiles(c, List.of()); }

    private static List<String> profiles(ClassInfo c, Collection<AnnotationInstance> member) {
        List<String> r = new ArrayList<>();
        for (var condition : member) {
            if (condition.name().equals(IF_PROFILE) && condition.value() != null) r.add(condition.value().asString());
            if (condition.name().equals(UNLESS_PROFILE) && condition.value() != null) r.add("!" + condition.value().asString());
        }
        var a = c.declaredAnnotation(IF_PROFILE);
        if (a != null && a.value() != null) r.add(a.value().asString());
        a = c.declaredAnnotation(UNLESS_PROFILE);
        if (a != null && a.value() != null) r.add("!" + a.value().asString());
        // Conditions on producer and declaring bean are conjunctive, not profile alternatives.
        if (r.stream().filter(p -> !p.startsWith("!")).distinct().count() > 1)
            return r.stream().distinct().map(p -> p.startsWith("!") ? p : "+" + p).toList();
        return r.isEmpty() ? null : List.copyOf(r);
    }

    private static String simple(DotName name) {
        String value = name.withoutPackagePrefix();
        int nested = value.lastIndexOf('$');
        return "@" + (nested >= 0 ? value.substring(nested + 1) : value);
    }

    static void reResolveUnresolved(List<BeanRecord> beans, List<InjectionPointRecord> ips, List<DependencyRecord> deps) {
        reResolveUnresolved(beans, ips, deps, Map.of(), Map.of());
    }

    private static void reResolveUnresolved(List<BeanRecord> beans,
            List<InjectionPointRecord> ips, List<DependencyRecord> deps,
            Map<Integer, Integer> specializedBeans, Map<Integer, String> classNames) {
        Map<String, List<BeanRecord>> byType = new HashMap<>();
        Map<Integer, Integer> classByBean = new HashMap<>();
        for (var b : beans) {
            classByBean.put(b.id(), b.classId());
            if (b.beanTypes() != null)
                for (String t : b.beanTypes()) byType.computeIfAbsent(assignableType(t), x -> new ArrayList<>()).add(b);
        }
        for (int i = 0; i < ips.size(); i++) {
            var ip = ips.get(i);
            if (ip.resolutionStatus() == ResolutionStatus.UNSUPPORTED_MECHANISM) continue;
            if (ip.resolvedBeanId() != null
                    || ip.resolutionStatus() == ResolutionStatus.AMBIGUOUS) continue;
            var candidates = byType.getOrDefault(assignableType(ip.targetType()), List.of());
            if (candidates.stream().anyMatch(b -> injectionCandidate(b) && qualifiersMatch(ip.qualifiers(), b.qualifiers())
                    && b.profiles() != null && !b.profiles().isEmpty())) {
                ips.set(i, profileContext(ip)); continue;
            }
            Selection selection = select(candidates, ip.qualifiers(), specializedBeans, classNames);
            if (selection.bean() != null) {
                var b = selection.bean();
                ips.set(i, ip.withResolution(b.id(), false, selection.trace()));
                Integer from = classByBean.get(ip.beanId());
                if (from != null) deps.add(new DependencyRecord(from, b.classId(), "CDI_INJECT", ip.id()));
            } else if (selection.ambiguous())
                ips.set(i, ip.withResolution(null, true, selection.trace()));
            else
                ips.set(i, ip.withResolution(null, false, selection.trace()));
        }
    }

    static InjectionPointRecord profileContext(InjectionPointRecord point) {
        return new InjectionPointRecord(point.id(), point.beanId(), point.kind(), point.targetType(),
                point.qualifiers(), point.fieldName(), null, ResolutionStatus.CONTEXT_REQUIRED,
                point.resolutionStrategy(), ResolutionReason.BUILD_PROFILE_CONTEXT_REQUIRED, ResolutionConfidence.LOW,
                List.of("A build profile is required to select conditional beans; profile lists use +required, !excluded, or unprefixed alternatives."),
                ResolutionTrace.EMPTY);
    }

    private record Selection(BeanRecord bean, boolean ambiguous, ResolutionTrace trace) {
    }

    static boolean injectionCandidate(BeanRecord bean) {
        return !"DECORATOR".equals(bean.kind()) && !"INTERCEPTOR".equals(bean.kind());
    }

    private static Selection select(List<BeanRecord> candidates, List<String> requiredQualifiers,
            Map<Integer, Integer> specializedBeans, Map<Integer, String> classNames) {
        List<ResolutionRule> appliedRules = new ArrayList<>(List.of(
                ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING, ResolutionRule.SPECIALIZATION,
                ResolutionRule.DEFAULT_BEAN_FALLBACK, ResolutionRule.ALTERNATIVE_PRIORITY, ResolutionRule.CDI_BEAN_KIND));
        Map<Integer, ResolutionCandidate> evidence = new LinkedHashMap<>();
        List<BeanRecord> qualified = new ArrayList<>();
        for (BeanRecord candidate : candidates) {
            if (!injectionCandidate(candidate)) {
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        CandidateDisposition.EXCLUDED, ResolutionReason.NOT_INJECTION_BEAN, List.of(ResolutionRule.CDI_BEAN_KIND)));
                continue;
            }
            if (qualifiersMatch(requiredQualifiers, candidate.qualifiers())) {
                qualified.add(candidate);
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        CandidateDisposition.ELIGIBLE, ResolutionReason.QUALIFIERS_MATCHED,
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING)));
            } else {
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        CandidateDisposition.EXCLUDED, ResolutionReason.QUALIFIER_MISMATCH,
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING)));
            }
        }

        Set<Integer> specializedTargets = new LinkedHashSet<>();
        for (BeanRecord candidate : qualified) {
            Integer target = specializedBeans.get(candidate.id());
            while (target != null && specializedTargets.add(target)) {
                target = specializedBeans.get(target);
            }
        }
        List<BeanRecord> active = new ArrayList<>();
        for (BeanRecord candidate : qualified) {
            if (specializedTargets.contains(candidate.id())) {
                Integer specializingBean = specializedBeans.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(candidate.id()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        specializingBean, CandidateDisposition.EXCLUDED,
                        ResolutionReason.SPECIALIZED_BY,
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING,
                                ResolutionRule.SPECIALIZATION)));
            } else {
                active.add(candidate);
            }
        }

        List<BeanRecord> nonDefault = active.stream().filter(bean -> !bean.isDefault()).toList();
        if (!nonDefault.isEmpty()) {
            for (BeanRecord candidate : active) {
                if (!candidate.isDefault()) continue;
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        CandidateDisposition.EXCLUDED, ResolutionReason.DEFAULT_BEAN_SUPPRESSED,
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING,
                                ResolutionRule.SPECIALIZATION, ResolutionRule.DEFAULT_BEAN_FALLBACK)));
            }
            active = new ArrayList<>(nonDefault);
        } else if (!active.isEmpty() && active.stream().allMatch(BeanRecord::isDefault)) {
            int best = active.stream().map(BeanResolver::defaultPriority)
                    .max(Integer::compareTo).orElseThrow();
            List<BeanRecord> winners = active.stream()
                    .filter(bean -> defaultPriority(bean) == best).toList();
            for (BeanRecord candidate : active) {
                boolean winner = winners.stream().anyMatch(bean -> bean.id() == candidate.id());
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        winner ? (winners.size() == 1 ? CandidateDisposition.SELECTED
                                : CandidateDisposition.ELIGIBLE) : CandidateDisposition.EXCLUDED,
                        winner ? (winners.size() == 1 ? ResolutionReason.DEFAULT_BEAN_FALLBACK_SELECTED
                                : ResolutionReason.SAME_PRIORITY_DEFAULT_BEAN) : ResolutionReason.LOWER_PRIORITY_DEFAULT_BEAN,
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING,
                                ResolutionRule.SPECIALIZATION, ResolutionRule.DEFAULT_BEAN_FALLBACK)));
            }
            ResolutionTrace trace = trace(evidence, appliedRules);
            return winners.size() == 1
                    ? new Selection(winners.getFirst(), false, trace)
                    : new Selection(null, true, trace);
        }

        var alternatives = active.stream()
                .filter(b -> b.isAlternative() && b.priority() != null)
                .toList();
        if (!alternatives.isEmpty()) {
            int best = alternatives.stream().mapToInt(BeanRecord::priority).max().orElseThrow();
            var winners = alternatives.stream().filter(b -> b.priority() == best).toList();
            for (BeanRecord candidate : active) {
                boolean winner = winners.stream().anyMatch(b -> b.id() == candidate.id());
                evidence.put(candidate.id(), candidateEvidence(candidate, classNames,
                        winner ? (winners.size() == 1 ? CandidateDisposition.SELECTED
                                : CandidateDisposition.ELIGIBLE) : CandidateDisposition.EXCLUDED,
                        winner ? (winners.size() == 1 ? ResolutionReason.HIGHEST_PRIORITY_ALTERNATIVE
                                : ResolutionReason.SAME_PRIORITY_ALTERNATIVE) : ResolutionReason.LOWER_PRIORITY_OR_NON_ALTERNATIVE,
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING,
                                ResolutionRule.SPECIALIZATION, ResolutionRule.ALTERNATIVE_PRIORITY)));
            }
            ResolutionTrace trace = trace(evidence, appliedRules);
            return winners.size() == 1
                    ? new Selection(winners.get(0), false, trace)
                    : new Selection(null, true, trace);
        }
        var normal = active.stream().filter(b -> !b.isAlternative()).toList();
        for (BeanRecord candidate : active) {
            boolean normalCandidate = !candidate.isAlternative();
            CandidateDisposition disposition = normalCandidate
                    ? (normal.size() == 1 ? CandidateDisposition.SELECTED
                            : CandidateDisposition.ELIGIBLE)
                    : CandidateDisposition.EXCLUDED;
            evidence.put(candidate.id(), candidateEvidence(candidate, classNames, disposition,
                    normalCandidate ? (normal.size() == 1 ? ResolutionReason.UNIQUE_ELIGIBLE_CANDIDATE
                            : ResolutionReason.MULTIPLE_ELIGIBLE_CANDIDATES) : ResolutionReason.INACTIVE_ALTERNATIVE,
                    List.of(ResolutionRule.TYPE_ASSIGNABILITY, ResolutionRule.QUALIFIER_MATCHING,
                            ResolutionRule.SPECIALIZATION, ResolutionRule.ALTERNATIVE_PRIORITY)));
        }
        ResolutionTrace trace = trace(evidence, appliedRules);
        return normal.size() == 1
                ? new Selection(normal.get(0), false, trace)
                : new Selection(null, normal.size() > 1, trace);
    }

    private static ResolutionCandidate candidateEvidence(BeanRecord bean,
            Map<Integer, String> classNames, CandidateDisposition disposition,
            ResolutionReason reason, List<ResolutionRule> rules) {
        return candidateEvidence(bean, classNames, null, disposition, reason, rules);
    }

    private static int defaultPriority(BeanRecord bean) {
        return bean.priority() == null ? 0 : bean.priority();
    }

    private static ResolutionCandidate candidateEvidence(BeanRecord bean,
            Map<Integer, String> classNames, Integer relatedBeanId,
            CandidateDisposition disposition, ResolutionReason reason, List<ResolutionRule> rules) {
        return new ResolutionCandidate(bean.id(), classNames.get(bean.classId()), relatedBeanId,
                disposition, reason, rules);
    }

    private static ResolutionTrace trace(Map<Integer, ResolutionCandidate> evidence,
            List<ResolutionRule> appliedRules) {
        return new ResolutionTrace(new ArrayList<>(evidence.values()), appliedRules,
                List.of(ResolutionRule.RUNTIME_CDI_EXTENSIONS, ResolutionRule.CONDITIONAL_BEAN_REGISTRATION,
                        ResolutionRule.PRODUCER_METHOD_SPECIALIZATION));
    }

    private static Map<Integer, Integer> specializedBeans(List<ClassInfo> classes,
            Map<DotName, Integer> classBeanIds) {
        Map<Integer, Integer> result = new HashMap<>();
        for (ClassInfo beanClass : classes) {
            if (!beanClass.hasDeclaredAnnotation(SPECIALIZES) || beanClass.superName() == null) {
                continue;
            }
            Integer specializingBean = classBeanIds.get(beanClass.name());
            Integer specializedBean = classBeanIds.get(beanClass.superName());
            if (specializingBean != null && specializedBean != null) {
                result.put(specializingBean, specializedBean);
            }
        }
        return result;
    }

    private static void inheritSpecializedQualifiers(List<BeanRecord> beans,
            Map<Integer, Integer> specializedBeans) {
        Map<Integer, BeanRecord> byId = new HashMap<>();
        beans.forEach(bean -> byId.put(bean.id(), bean));
        for (int i = 0; i < beans.size(); i++) {
            BeanRecord bean = beans.get(i);
            if (!specializedBeans.containsKey(bean.id())) continue;
            LinkedHashSet<String> inherited = new LinkedHashSet<>();
            collectSpecializedQualifiers(bean.id(), specializedBeans, byId,
                    inherited, new HashSet<>());
            inherited.addAll(bean.qualifiers());
            boolean custom = inherited.stream().anyMatch(q -> !q.equals(BuiltinQualifier.ANY.annotation())
                    && !q.equals(BuiltinQualifier.DEFAULT.annotation()) && !q.startsWith(BuiltinQualifier.NAMED.annotation()));
            if (custom) inherited.remove(BuiltinQualifier.DEFAULT.annotation());
            inherited.add(BuiltinQualifier.ANY.annotation());
            BeanRecord updated = new BeanRecord(bean.id(), bean.classId(), bean.kind(),
                    bean.scope(), new ArrayList<>(inherited), bean.stereotypes(),
                    bean.isAlternative(), bean.isDefault(), bean.priority(), bean.profiles(),
                    bean.declaringClassId(), bean.memberName(), bean.beanTypes());
            beans.set(i, updated);
            byId.put(updated.id(), updated);
        }
    }

    private static void collectSpecializedQualifiers(int beanId,
            Map<Integer, Integer> specializedBeans, Map<Integer, BeanRecord> beans,
            Set<String> result, Set<Integer> visited) {
        if (!visited.add(beanId)) return;
        Integer parentId = specializedBeans.get(beanId);
        if (parentId == null) return;
        collectSpecializedQualifiers(parentId, specializedBeans, beans, result, visited);
        BeanRecord parent = beans.get(parentId);
        if (parent != null) result.addAll(parent.qualifiers());
    }

    private static boolean qualifiersMatch(List<String> required, List<String> available) {
        for (String q : required) if (!BuiltinQualifier.ANY.annotation().equals(q) && !available.contains(q)) return false;
        return true;
    }

    static void addNonBeanDependencies(IndexView index, List<BeanRecord> beans,
            List<DependencyRecord> deps, Map<String, Integer> ids) {
        Set<DotName> known = new HashSet<>();
        index.getKnownClasses().forEach(c -> known.add(c.name()));
        Map<Integer, String> names = new HashMap<>();
        ids.forEach((name, id) -> names.put(id, name));
        Set<String> beanClasses = new HashSet<>();
        beans.forEach(b -> beanClasses.add(names.get(b.classId())));
        for (ClassInfo c : index.getKnownClasses()) {
            String name = c.name().toString();
            if (beanClasses.contains(name)) continue;
            int from = ids.computeIfAbsent(name, x -> ids.size() + 1);
            Set<DotName> refs = new HashSet<>();
            c.fields().forEach(f -> reference(f.type(), c.name(), known, refs));
            for (MethodInfo m : c.methods()) {
                m.parameterTypes().forEach(t -> reference(t, c.name(), known, refs));
                reference(m.returnType(), c.name(), known, refs);
            }
            for (DotName ref : refs) {
                int to = ids.computeIfAbsent(ref.toString(), x -> ids.size() + 1);
                deps.add(new DependencyRecord(from, to, "CLASS_REFERENCE", null));
            }
        }
    }

    private static void reference(Type type, DotName owner, Set<DotName> known, Set<DotName> result) {
        DotName ref = type.name();
        if (ref != null && !ref.equals(owner) && known.contains(ref)) result.add(ref);
    }
}
