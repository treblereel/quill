package org.treblereel.mcp.core;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

/** Quarkus REST registrations, only for classes with confirmed module build context. */
public final class QuarkusRestDiscovery {
    private static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final Set<String> DEFINING = Set.of("jakarta.ws.rs.Path",
            "jakarta.ws.rs.ApplicationPath", "jakarta.ws.rs.ext.Provider");
    private static final Set<String> REQUEST_PARAMETERS = Set.of("jakarta.ws.rs.QueryParam", "jakarta.ws.rs.PathParam",
            "jakarta.ws.rs.HeaderParam", "jakarta.ws.rs.CookieParam", "jakarta.ws.rs.FormParam", "jakarta.ws.rs.MatrixParam",
            "jakarta.ws.rs.BeanParam", "org.jboss.resteasy.reactive.RestQuery",
            "org.jboss.resteasy.reactive.RestPath", "org.jboss.resteasy.reactive.RestHeader",
            "org.jboss.resteasy.reactive.RestCookie", "org.jboss.resteasy.reactive.RestForm", "org.jboss.resteasy.reactive.RestMatrix");

    public static Map<String, String> scopes(IndexView index, Set<String> confirmedClasses) {
        Map<String, String> result = new LinkedHashMap<>();
        List<ClassInfo> classes = index.getKnownClasses().stream()
                .filter(c -> confirmedClasses.contains(c.name().toString())).toList();
        for (ClassInfo c : classes) {
            if (!BeanResolver.frameworkBeanClass(c) || BeanResolver.vetoed(c, index)) continue;
            boolean defining = c.declaredAnnotations().stream().anyMatch(a -> DEFINING.contains(a.name().toString()));
            ClassInfo parent = c.superName() == null ? null : index.getClassByName(c.superName());
            boolean inheritedResource = parent != null && Modifier.isAbstract(parent.flags()) && parent.hasDeclaredAnnotation(PATH)
                    && index.getAllKnownSubclasses(parent.name()).size() == 1;
            if (defining || inheritedResource) result.put(c.name().toString(), c.hasDeclaredAnnotation(PATH)
                    && requestFields(c, index, new HashSet<>()) ? "@RequestScoped" : "@Singleton");
        }
        for (ClassInfo contract : classes) {
            if (!contract.isInterface() || !contract.hasDeclaredAnnotation(PATH)) continue;
            var implementations = index.getAllKnownImplementors(contract.name()).stream().filter(BeanResolver::frameworkBeanClass)
                    .toList();
            if (implementations.size() == 1 && confirmedClasses.contains(implementations.getFirst().name().toString()))
                result.putIfAbsent(implementations.getFirst().name().toString(), "@Dependent");
        }
        return Map.copyOf(result);
    }

    private static boolean requestFields(ClassInfo c, IndexView index, Set<DotName> seen) {
        if (!seen.add(c.name())) return false;
        boolean field = c.fields().stream().anyMatch(f -> f.annotations().stream()
                .anyMatch(a -> REQUEST_PARAMETERS.contains(a.name().toString())));
        if (field) return true;
        ClassInfo parent = c.superName() == null ? null : index.getClassByName(c.superName());
        return parent != null && requestFields(parent, index, seen);
    }

    private QuarkusRestDiscovery() {}
}
