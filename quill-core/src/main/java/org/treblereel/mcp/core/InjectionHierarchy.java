package org.treblereel.mcp.core;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

/**
 * Superclass traversal shared by the CDI and Spring resolvers. Both containers inject the
 * annotated fields and initializer methods declared by every superclass of a bean class, except
 * initializer methods overridden lower in the hierarchy.
 */
final class InjectionHierarchy {

    private InjectionHierarchy() {
    }

    /** Superclasses from the direct parent upwards, excluding Object and stopping at unindexed types. */
    static List<ClassInfo> superclasses(ClassInfo bean, IndexView index) {
        List<ClassInfo> result = new ArrayList<>();
        Set<DotName> visited = new HashSet<>();
        for (DotName name = bean.superName(); name != null && !DotName.OBJECT_NAME.equals(name)
                && visited.add(name);) {
            ClassInfo parent = index.getClassByName(name);
            if (parent == null) break;
            result.add(parent);
            name = parent.superName();
        }
        return result;
    }

    /**
     * Whether {@code method}, declared by a superclass, is overridden by any class in
     * {@code lowerClasses} (the bean class and the superclasses below the declaring one).
     * Private methods are never overridden; package-private ones only from the same package.
     */
    static boolean overridden(MethodInfo method, List<ClassInfo> lowerClasses) {
        if (Modifier.isPrivate(method.flags()) || Modifier.isStatic(method.flags())) return false;
        boolean packagePrivate = !Modifier.isPublic(method.flags())
                && !Modifier.isProtected(method.flags());
        String declaringPackage = method.declaringClass().name().packagePrefix();
        for (ClassInfo lower : lowerClasses) {
            if (packagePrivate && !java.util.Objects.equals(declaringPackage, lower.name().packagePrefix())) {
                continue;
            }
            for (MethodInfo candidate : lower.methods()) {
                if (Modifier.isPrivate(candidate.flags()) || Modifier.isStatic(candidate.flags())) continue;
                if (candidate.name().equals(method.name())
                        && candidate.parameterTypes().equals(method.parameterTypes())) return true;
            }
        }
        return false;
    }

    /** Classes below {@code ancestor} in the chain {@code bean -> superclasses}. */
    static List<ClassInfo> below(ClassInfo bean, List<ClassInfo> superclasses, ClassInfo ancestor) {
        List<ClassInfo> result = new ArrayList<>();
        result.add(bean);
        for (ClassInfo parent : superclasses) {
            if (parent == ancestor) break;
            result.add(parent);
        }
        return result;
    }

    /** Member label for an injection point declared by a superclass: {@code Parent.member}. */
    static String inheritedMember(ClassInfo declaring, String member) {
        return declaring.simpleName() + "." + member;
    }
}
