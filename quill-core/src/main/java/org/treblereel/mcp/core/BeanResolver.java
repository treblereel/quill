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
            ALTERNATIVE = n("jakarta.enterprise.inject.Alternative"), PRIORITY = n("jakarta.annotation.Priority"),
            INTERCEPTOR = n("jakarta.interceptor.Interceptor"), DECORATOR = n("jakarta.decorator.Decorator"),
            IF_PROFILE = n("io.quarkus.arc.profile.IfBuildProfile"),
            UNLESS_PROFILE = n("io.quarkus.arc.profile.UnlessBuildProfile");
    private static final Set<DotName> SCOPES = Set.of(n("jakarta.enterprise.context.ApplicationScoped"),
            n("jakarta.enterprise.context.RequestScoped"), n("jakarta.enterprise.context.SessionScoped"),
            n("jakarta.enterprise.context.ConversationScoped"), n("jakarta.enterprise.context.Dependent"),
            n("jakarta.inject.Singleton"));
    private static final Set<DotName> CDI_SIGNALS = Set.of(n("jakarta.enterprise.context.ApplicationScoped"),
            n("jakarta.enterprise.context.RequestScoped"), n("jakarta.enterprise.context.SessionScoped"),
            n("jakarta.enterprise.context.ConversationScoped"), n("jakarta.enterprise.context.Dependent"),
            PRODUCES, STEREOTYPE, INTERCEPTOR, DECORATOR);

    public static boolean isCdiProject(Index index) {
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
            if (!isBeanClass(c, lookup)) continue;
            int classId = classIds.computeIfAbsent(c.name().toString(), x -> classIds.size() + 1);
            int ownerId = nextBean++;
            classBeanIds.put(c.name(), ownerId);
            beans.add(new BeanRecord(ownerId, classId, beanKind(c), scope(c.declaredAnnotations(), lookup),
                    qualifiers(c.declaredAnnotations(), lookup, true), stereotypes(c, lookup),
                    hasMeta(c.declaredAnnotations(), ALTERNATIVE, lookup), priority(c.declaredAnnotations(), lookup),
                    profiles(c), null, null, typeClosure(Type.create(c.name(), Type.Kind.CLASS), lookup)));
            for (FieldInfo f : c.fields())
                if (f.hasAnnotation(PRODUCES)) {
                    beans.add(new BeanRecord(nextBean++, classId, "PRODUCER_FIELD", scope(f.annotations(), lookup),
                            qualifiers(f.annotations(), lookup, true), List.of(),
                            hasAnnotation(f.annotations(), ALTERNATIVE) || isAlternative(c, lookup),
                            first(priority(f.annotations(), lookup), priority(c.declaredAnnotations(), lookup)),
                            profiles(c), classId, f.name(), typeClosure(f.type(), lookup)));
                }
            for (MethodInfo m : c.methods())
                if (methodHas(m, PRODUCES)) {
                    int producerId = nextBean++;
                    producerIds.put(m, producerId);
                    Collection<AnnotationInstance> annotations = methodAnnotations(m);
                    beans.add(new BeanRecord(producerId, classId, "PRODUCER_METHOD", scope(annotations, lookup),
                            qualifiers(annotations, lookup, true), List.of(),
                            hasAnnotation(annotations, ALTERNATIVE) || isAlternative(c, lookup),
                            first(priority(annotations, lookup), priority(c.declaredAnnotations(), lookup)),
                            profiles(c), classId, m.name(), typeClosure(m.returnType(), lookup)));
                }
        }
        int nextIp = 1;
        for (ClassInfo c : classes) {
            Integer ownerId = classBeanIds.get(c.name());
            if (ownerId == null) continue;
            for (FieldInfo f : c.fields())
                if (f.hasAnnotation(INJECT)) {
                    ips.add(ip(nextIp++, ownerId, "FIELD", f.type(), qualifiers(f.annotations(), lookup, false), f.name()));
                }
            for (MethodInfo m : c.methods()) {
                boolean injected = methodHas(m, INJECT), producer = methodHas(m, PRODUCES);
                if (!injected && !producer) continue;
                int parameterOwner = producer ? producerIds.getOrDefault(m, ownerId) : ownerId;
                String kind = "<init>".equals(m.name()) ? "CONSTRUCTOR_PARAM" : "METHOD_PARAM";
                for (int p = 0; p < m.parametersCount(); p++)
                    ips.add(ip(nextIp++, parameterOwner, kind, m.parameterType(p),
                            qualifiers(parameterAnnotations(m, p), lookup, false), m.name()));
            }
        }
        reResolveUnresolved(beans, ips, deps);
        addNonBeanDependencies(applicationIndex, beans, deps, classIds);
        return new ResolutionResult(beans, ips, deps, classIds, List.of());
    }

    private static InjectionPointRecord ip(int id, int owner, String kind, Type type, List<String> qs, String member) {
        return new InjectionPointRecord(id, owner, kind, typeName(type), qs, member, null, false);
    }

    private static boolean isBeanClass(ClassInfo c, IndexView index) {
        if (c.isAnnotation() || c.isInterface() || Modifier.isAbstract(c.flags())) return false;
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

    private static List<String> stereotypes(ClassInfo c, IndexView index) {
        return c.declaredAnnotations().stream().filter(a -> metaHas(a.name(), STEREOTYPE, index, new HashSet<>()))
                .map(a -> simple(a.name())).toList();
    }

    private static List<String> qualifiers(Collection<AnnotationInstance> annotations, IndexView index, boolean bean) {
        List<String> result = new ArrayList<>();
        for (var a : annotations) {
            if (a.name().equals(NAMED)) {
                String value = a.value() == null ? "" : a.value().asString();
                result.add(value.isEmpty() ? "@Named" : "@Named(\"" + value + "\")");
            } else if (metaHas(a.name(), QUALIFIER, index, new HashSet<>())) result.add(simple(a.name()));
        }
        if (bean) {
            boolean custom = result.stream().anyMatch(q -> !q.equals("@Any") && !q.equals("@Default") && !q.startsWith("@Named"));
            if (!custom && !result.contains("@Default")) result.add("@Default");
            if (!result.contains("@Any")) result.add("@Any");
        } else if (result.isEmpty()) result.add("@Default");
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

    private static List<String> typeClosure(Type type, IndexView index) {
        LinkedHashSet<String> r = new LinkedHashSet<>();
        addType(type, index, r, new HashSet<>());
        return List.copyOf(r);
    }

    private static void addType(Type type, IndexView index, Set<String> result, Set<DotName> visited) {
        DotName name = type.name();
        if (name == null || !visited.add(name)) return;
        result.add(name.toString());
        ClassInfo c = index.getClassByName(name);
        if (c == null) return;
        for (Type i : c.interfaceTypes()) addType(i, index, result, visited);
        Type parent = c.superClassType();
        if (parent != null && !"java.lang.Object".equals(parent.name().toString()))
            addType(parent, index, result, visited);
    }

    private static String typeName(Type type) {
        return type.name() == null ? type.toString() : type.name().toString();
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

    private static List<String> profiles(ClassInfo c) {
        List<String> r = new ArrayList<>();
        var a = c.declaredAnnotation(IF_PROFILE);
        if (a != null && a.value() != null) r.add(a.value().asString());
        a = c.declaredAnnotation(UNLESS_PROFILE);
        if (a != null && a.value() != null) r.add("!" + a.value().asString());
        return r.isEmpty() ? null : List.copyOf(r);
    }

    private static String simple(DotName name) {
        String value = name.withoutPackagePrefix();
        int nested = value.lastIndexOf('$');
        return "@" + (nested >= 0 ? value.substring(nested + 1) : value);
    }

    static void reResolveUnresolved(List<BeanRecord> beans, List<InjectionPointRecord> ips, List<DependencyRecord> deps) {
        Map<String, List<BeanRecord>> byType = new HashMap<>();
        Map<Integer, Integer> classByBean = new HashMap<>();
        for (var b : beans) {
            classByBean.put(b.id(), b.classId());
            if (b.beanTypes() != null)
                for (String t : b.beanTypes()) byType.computeIfAbsent(t, x -> new ArrayList<>()).add(b);
        }
        for (int i = 0; i < ips.size(); i++) {
            var ip = ips.get(i);
            if (ip.resolvedBeanId() != null || ip.isAmbiguous()) continue;
            var candidates = byType.getOrDefault(ip.targetType(), List.of()).stream().filter(b -> qualifiersMatch(ip.qualifiers(), b.qualifiers())).toList();
            Selection selection = select(candidates);
            if (selection.bean() != null) {
                var b = selection.bean();
                ips.set(i, new InjectionPointRecord(ip.id(), ip.beanId(), ip.kind(), ip.targetType(), ip.qualifiers(), ip.fieldName(), b.id(), false));
                Integer from = classByBean.get(ip.beanId());
                if (from != null) deps.add(new DependencyRecord(from, b.classId(), "CDI_INJECT", ip.id()));
            } else if (selection.ambiguous())
                ips.set(i, new InjectionPointRecord(ip.id(), ip.beanId(), ip.kind(), ip.targetType(), ip.qualifiers(), ip.fieldName(), null, true));
        }
    }

    private record Selection(BeanRecord bean, boolean ambiguous) {
    }

    private static Selection select(List<BeanRecord> candidates) {
        var alternatives = candidates.stream().filter(b -> b.isAlternative() && b.priority() != null).sorted(Comparator.comparing(BeanRecord::priority)).toList();
        if (!alternatives.isEmpty()) {
            int best = alternatives.get(0).priority();
            var winners = alternatives.stream().filter(b -> b.priority() == best).toList();
            return winners.size() == 1 ? new Selection(winners.get(0), false) : new Selection(null, true);
        }
        var normal = candidates.stream().filter(b -> !b.isAlternative()).toList();
        return normal.size() == 1 ? new Selection(normal.get(0), false) : new Selection(null, normal.size() > 1);
    }

    private static boolean qualifiersMatch(List<String> required, List<String> available) {
        for (String q : required) if (!"@Any".equals(q) && !available.contains(q)) return false;
        return true;
    }

    static void addNonBeanDependencies(Index index, List<BeanRecord> beans, List<DependencyRecord> deps, Map<String, Integer> ids) {
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
