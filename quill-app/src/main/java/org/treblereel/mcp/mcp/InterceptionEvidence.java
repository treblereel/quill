package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Orders the decorators and interceptors that apply to a bean. Agents misread CDI ordering
 * (for example reversing a decorator chain), so the order is computed from @Priority: enabled
 * interceptors and decorators with smaller priority values are called first (CDI 4.1 9.4, 8.2).
 */
final class InterceptionEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> INTERCEPTOR_BINDING = Set.of(
            "jakarta.interceptor.InterceptorBinding", "javax.interceptor.InterceptorBinding");
    private static final Comparator<BeanRecord> BY_PRIORITY = Comparator.comparing(
            (BeanRecord bean) -> bean.priority() == null ? Integer.MAX_VALUE : bean.priority());

    private InterceptionEvidence() {}

    static void append(ObjectNode root, ReadQueryContext reader, ClassRecord cls, BeanRecord bean) {
        if (!"CLASS".equals(bean.kind())) return;
        List<BeanRecord> decorators = reader.beans.findBeans(Map.of("kind", "DECORATOR"));
        List<BeanRecord> interceptors = reader.beans.findBeans(Map.of("kind", "INTERCEPTOR"));
        if (decorators.isEmpty() && interceptors.isEmpty()) return;
        List<Integer> classIds = new ArrayList<>();
        decorators.forEach(value -> classIds.add(value.classId()));
        interceptors.forEach(value -> classIds.add(value.classId()));
        classIds.add(cls.id());
        Map<Integer, ClassRecord> classes = reader.symbols.findClassesByIds(classIds);
        Map<Integer, List<ClassAnnotationRecord>> annotations = reader.symbols.findClassAnnotations(classIds);

        ObjectNode interception = root.putObject("interception");
        interception.put("order_rule", "Enabled interceptors, then decorators, run in ascending "
                + "@Priority order; the first entry is called first");
        Set<String> beanTypes = new LinkedHashSet<>(bean.beanTypes() == null ? List.of() : bean.beanTypes());
        beanTypes.remove("java.lang.Object");
        ArrayNode decorated = interception.putArray("decorators");
        decorators.stream().sorted(BY_PRIORITY).forEach(decorator -> {
            ClassRecord decoratorClass = classes.get(decorator.classId());
            if (decoratorClass == null || decorator.beanTypes() == null) return;
            List<String> shared = decorator.beanTypes().stream()
                    .filter(beanTypes::contains)
                    .filter(type -> !type.equals(decoratorClass.className())).toList();
            if (shared.isEmpty()) return;
            appendEntry(decorated.addObject(), decoratorClass, decorator).set("decorated_types",
                    JSON.valueToTree(shared));
        });

        Set<String> beanAnnotations = new LinkedHashSet<>();
        annotations.getOrDefault(cls.id(), List.of())
                .forEach(annotation -> beanAnnotations.add(annotation.annotationName()));
        ArrayNode intercepted = interception.putArray("interceptors");
        interceptors.stream().sorted(BY_PRIORITY).forEach(interceptor -> {
            ClassRecord interceptorClass = classes.get(interceptor.classId());
            if (interceptorClass == null) return;
            List<String> bindings = bindings(annotations.getOrDefault(interceptor.classId(), List.of()));
            if (bindings.isEmpty()) return;
            List<String> classLevel = bindings.stream().filter(beanAnnotations::contains).toList();
            List<String> methodLevel = bindings.stream().filter(binding -> !classLevel.contains(binding))
                    .filter(binding -> reader.symbols.findAnnotatedSymbols(binding, "METHOD", false, 500, 0)
                            .stream().anyMatch(symbol -> symbol.classId() == cls.id())).toList();
            if (classLevel.isEmpty() && methodLevel.isEmpty()) return;
            ObjectNode entry = appendEntry(intercepted.addObject(), interceptorClass, interceptor);
            entry.set("bindings", JSON.valueToTree(bindings));
            entry.put("applies_to", classLevel.isEmpty() ? "annotated_methods" : "all_business_methods");
        });
        interception.putArray("limitations")
                .add("Bindings are matched by annotation name; binding member values and "
                        + "@Nonbinding members are not compared")
                .add("Enablement through beans.xml is not indexed; entries without @Priority may be disabled");
    }

    private static ObjectNode appendEntry(ObjectNode node, ClassRecord cls, BeanRecord bean) {
        node.put("class", cls.className());
        if (bean.priority() == null) node.putNull("priority");
        else node.put("priority", bean.priority());
        node.put("enabled", bean.priority() != null ? "priority" : "unknown_without_priority");
        return node;
    }

    /** Interceptor binding types declared on the interceptor class itself. */
    private static List<String> bindings(List<ClassAnnotationRecord> annotations) {
        Set<String> direct = new LinkedHashSet<>();
        annotations.stream().filter(ClassAnnotationRecord::direct)
                .forEach(annotation -> direct.add(annotation.annotationName()));
        return annotations.stream()
                .filter(annotation -> INTERCEPTOR_BINDING.contains(annotation.annotationName()))
                .map(ClassAnnotationRecord::viaAnnotation)
                .filter(direct::contains).distinct().sorted().toList();
    }
}
