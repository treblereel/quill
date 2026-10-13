package org.treblereel.mcp.core;

import java.lang.reflect.Modifier;
import java.util.*;
import org.jboss.jandex.*;

/** Bounded Java method inheritance with class generic arguments substituted for override matching. */
final class EndpointHierarchy {
    final ClassInfo root;
    final Map<DotName, Node> nodes = new LinkedHashMap<>();
    boolean complete = true;
    private final IndexView lookup;

    EndpointHierarchy(ClassInfo root, IndexView lookup) {
        this.root = root;
        this.lookup = lookup;
        visit(root, Map.of(), new HashSet<>(), 0);
    }

    private void visit(ClassInfo type, Map<String, Type> bindings, Set<DotName> path, int depth) {
        if (type.name().toString().equals("java.lang.Object")) return;
        if (depth >= 64 || nodes.size() >= 128 || !path.add(type.name())) {complete = false; return;}
        if (nodes.containsKey(type.name())) {path.remove(type.name()); return;}
        Node node = new Node(type, bindings, depth);
        nodes.put(type.name(), node);
        for (Type parent : parents(type)) {
            if (parent.name().toString().equals("java.lang.Object")) continue;
            ClassInfo definition = lookup.getClassByName(parent.name());
            if (definition == null) {complete = false; continue;}
            Map<String, Type> arguments = new HashMap<>();
            List<Type> supplied = parent.kind() == Type.Kind.PARAMETERIZED_TYPE
                    ? parent.asParameterizedType().arguments() : List.of();
            for (int i = 0; i < definition.typeParameters().size(); i++) {
                TypeVariable variable = definition.typeParameters().get(i);
                Type argument = i < supplied.size() ? substitute(supplied.get(i), bindings, new HashSet<>())
                        : variable.bounds().stream().findFirst().orElse(Type.create(Object.class));
                arguments.put(variable.identifier(), argument);
            }
            visit(definition, Map.copyOf(arguments), path, depth + 1);
        }
        path.remove(type.name());
    }

    private static Type substitute(Type type, Map<String, Type> bindings, Set<String> path) {
        if (type.kind() == Type.Kind.ARRAY) return ArrayType.create(
                substitute(type.asArrayType().constituent(), bindings, new HashSet<>(path)),
                type.asArrayType().dimensions());
        if (type.kind() != Type.Kind.TYPE_VARIABLE) return type;
        String identifier = type.asTypeVariable().identifier();
        if (!path.add(identifier)) return type;
        Type replacement = bindings.get(identifier);
        if (replacement == null) replacement = type.asTypeVariable().bounds().stream()
                .findFirst().orElse(Type.create(Object.class));
        return substitute(replacement, bindings, path);
    }

    List<MethodInfo> effectiveMethods() {
        Map<String, MethodInfo> methods = new LinkedHashMap<>();
        // Java implementation ownership: class methods precede interface default methods.
        nodes.values().stream().filter(n -> !n.type.isInterface())
                .sorted(Comparator.comparingInt(Node::depth)).forEach(n -> {
                    for (MethodInfo method : n.type.methods()) {
                        if (usable(method) && visible(method)) methods.putIfAbsent(key(n, method), method);
                    }
                });
        if (root.isInterface()) {
            Node node = nodes.get(root.name());
            for (MethodInfo method : root.methods()) if (usable(method)) methods.put(key(node, method), method);
        }
        Map<String, List<MethodInfo>> defaults = new LinkedHashMap<>();
        for (Node node : nodes.values()) if (node.type.isInterface()) {
            for (MethodInfo method : node.type.methods()) {
                if (usable(method) && visible(method))
                    defaults.computeIfAbsent(key(node, method), ignored -> new ArrayList<>()).add(method);
            }
        }
        defaults.forEach((key, candidates) -> {
            List<MethodInfo> specific = mostSpecific(candidates);
            if (!methods.containsKey(key) && specific.size() == 1
                    && !Modifier.isAbstract(specific.getFirst().flags())) methods.put(key, specific.getFirst());
        });
        // Incomplete ancestors cannot establish which inherited body wins.
        return methods.values().stream().filter(m -> complete || m.declaringClass().name().equals(root.name()))
                .sorted(Comparator.comparing(MethodInfo::name).thenComparing(EndpointHierarchy::descriptor)).toList();
    }

    MethodInfo matching(Node node, String key) {
        return node.type.methods().stream().filter(EndpointHierarchy::usable).filter(this::visible)
                .filter(m -> key(node, m).equals(key)).findFirst().orElse(null);
    }

    String key(MethodInfo method) {return key(nodes.get(method.declaringClass().name()), method);}

    private static String key(Node node, MethodInfo method) {
        return method.name() + "(" + String.join("", method.parameterTypes().stream()
                .map(t -> erased(t, node.bindings, method.typeParameters(), new HashSet<>())).toList()) + ")";
    }

    private static String erased(Type type, Map<String, Type> bindings,
            List<TypeVariable> methodVariables, Set<String> path) {
        return switch (type.kind()) {
            case CLASS, PARAMETERIZED_TYPE -> "L" + type.name().toString().replace('.', '/') + ";";
            case ARRAY -> "[".repeat(type.asArrayType().dimensions())
                    + erased(type.asArrayType().constituent(), bindings, methodVariables, path);
            case TYPE_VARIABLE -> {
                TypeVariable variable = type.asTypeVariable();
                if (!path.add(variable.identifier())) yield "Ljava/lang/Object;";
                TypeVariable local = methodVariables.stream()
                        .filter(v -> v.identifier().equals(variable.identifier())).findFirst().orElse(null);
                Type replacement = local == null ? bindings.get(variable.identifier()) : null;
                if (replacement == null) replacement = (local == null ? variable : local).bounds().stream()
                        .findFirst().orElse(Type.create(Object.class));
                yield erased(replacement, bindings, methodVariables, path);
            }
            case PRIMITIVE -> switch (type.asPrimitiveType().primitive()) {
                case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case CHAR -> "C";
                case INT -> "I"; case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D";
            };
            case VOID -> "V";
            default -> "Ljava/lang/Object;";
        };
    }

    List<MethodInfo> mostSpecific(List<MethodInfo> candidates) {
        return candidates.stream().distinct().filter(candidate -> candidates.stream().noneMatch(other ->
                !candidate.declaringClass().name().equals(other.declaringClass().name())
                && descendant(other.declaringClass().name(), candidate.declaringClass().name(), new HashSet<>())))
                .toList();
    }

    private boolean descendant(DotName child, DotName ancestor, Set<DotName> visited) {
        if (child.equals(ancestor)) return true;
        Node node = nodes.get(child);
        if (node == null || !visited.add(child)) return false;
        return parents(node.type).stream().anyMatch(p -> descendant(p.name(), ancestor, visited));
    }

    private boolean visible(MethodInfo method) {
        if (method.declaringClass().name().equals(root.name())) return true;
        int flags = method.flags();
        if (Modifier.isPrivate(flags) || Modifier.isStatic(flags)) return false;
        if (Modifier.isPublic(flags) || Modifier.isProtected(flags)) return true;
        String packageName = packageName(method.declaringClass());
        // Package-private inheritance is interrupted by a superclass in another package.
        ClassInfo current = root;
        Set<DotName> visited = new HashSet<>();
        while (current != null && visited.add(current.name())) {
            if (!packageName(current).equals(packageName)) return false;
            if (current.name().equals(method.declaringClass().name())) return true;
            Node parent = current.superName() == null ? null : nodes.get(current.superName());
            current = parent == null ? null : parent.type;
        }
        return false;
    }

    private static String packageName(ClassInfo type) {
        String name = type.name().toString();
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    static boolean usable(MethodInfo method) {
        return !method.name().startsWith("<") && (method.flags() & (0x1000 | 0x40)) == 0;
    }

    static String descriptor(MethodInfo method) {
        return method.descriptor(identifier -> java.util.stream.Stream.concat(method.typeParameters().stream(),
                        method.declaringClass().typeParameters().stream())
                .filter(v -> v.identifier().equals(identifier)).findFirst()
                .flatMap(v -> v.bounds().stream().findFirst()).orElse(Type.create(Object.class)));
    }

    static List<Type> parents(ClassInfo type) {
        List<Type> result = new ArrayList<>(type.interfaceTypes());
        if (type.superClassType() != null) result.add(type.superClassType());
        return result;
    }

    record Node(ClassInfo type, Map<String, Type> bindings, int depth) {}
}
