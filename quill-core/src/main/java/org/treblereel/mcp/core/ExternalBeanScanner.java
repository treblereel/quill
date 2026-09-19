package org.treblereel.mcp.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ExternalBeanRecord;
import org.treblereel.mcp.model.ExternalInjectionPointRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

/** Extracts DI beans from the dependency index without adding them to application classes. */
public final class ExternalBeanScanner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIG_PROPERTY =
            "org.eclipse.microprofile.config.inject.ConfigProperty";
    private static final String SPRING_VALUE =
            "org.springframework.beans.factory.annotation.Value";

    private ExternalBeanScanner() {}

    public static List<ExternalBeanRecord> scan(IndexView dependencyIndex, List<Path> jars) {
        if (dependencyIndex == null || jars.isEmpty()) return List.of();

        List<FrameworkResolution> resolutions = new ArrayList<>();
        if (SpringResolver.isSpringProject(dependencyIndex)) {
            resolutions.add(new FrameworkResolution(
                    "spring", SpringResolver.resolve(dependencyIndex, null)));
        }
        if (BeanResolver.isCdiProject(dependencyIndex)) {
            resolutions.add(new FrameworkResolution(
                    "cdi", BeanResolver.resolve(dependencyIndex, null)));
        }
        if (resolutions.isEmpty()) return List.of();

        Set<String> beanClasses = new HashSet<>();
        for (FrameworkResolution resolution : resolutions) {
            beanClasses.addAll(resolution.result().classNameToId().keySet());
        }
        Map<String, Path> origins = locateOrigins(beanClasses, jars);

        List<ExternalBeanRecord> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int nextId = 1;
        for (FrameworkResolution resolution : resolutions) {
            Map<Integer, String> classNames = reverse(resolution.result().classNameToId());
            Map<Integer, List<InjectionPointRecord>> injections = new HashMap<>();
            for (InjectionPointRecord injection : resolution.result().injectionPoints()) {
                injections.computeIfAbsent(injection.beanId(), ignored -> new ArrayList<>())
                        .add(injection);
            }
            for (BeanRecord bean : resolution.result().beans()) {
                String className = classNames.get(bean.classId());
                if (className == null) continue;
                String key = resolution.framework() + '|' + className + '|' + bean.kind()
                        + '|' + String.valueOf(bean.memberName());
                if (!seen.add(key)) continue;
                Path jar = origins.get(className);
                result.add(new ExternalBeanRecord(nextId++, className, bean.kind(), bean.scope(),
                        bean.qualifiers(), bean.stereotypes(), bean.isAlternative(),
                        bean.isDefault(), bean.priority(), bean.profiles(), bean.memberName(),
                        bean.beanTypes(), resolution.framework(), coordinates(jar),
                        jar == null ? null : jar.toString(), externalInjections(
                                dependencyIndex.getClassByName(className),
                                injections.getOrDefault(bean.id(), List.of()))));
            }
        }
        result.sort(Comparator.comparing(ExternalBeanRecord::className)
                .thenComparing(ExternalBeanRecord::framework)
                .thenComparing(ExternalBeanRecord::kind)
                .thenComparing(value -> String.valueOf(value.memberName())));
        List<ExternalBeanRecord> numbered = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            ExternalBeanRecord bean = result.get(i);
            numbered.add(new ExternalBeanRecord(i + 1, bean.className(), bean.kind(),
                    bean.scope(), bean.qualifiers(), bean.stereotypes(), bean.alternative(),
                    bean.defaultBean(), bean.priority(), bean.profiles(), bean.memberName(),
                    bean.beanTypes(), bean.framework(), bean.artifact(), bean.jarPath(),
                    bean.injectionPoints()));
        }
        return List.copyOf(numbered);
    }

    public static String injectionsJson(List<ExternalInjectionPointRecord> injections) {
        try {
            return JSON.writeValueAsString(injections);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize external injection points", e);
        }
    }

    private static List<ExternalInjectionPointRecord> externalInjections(
            ClassInfo owner, List<InjectionPointRecord> injections) {
        List<ExternalInjectionPointRecord> result = new ArrayList<>();
        Map<String, Integer> parameterCursors = new HashMap<>();
        for (InjectionPointRecord injection : injections) {
            Collection<AnnotationInstance> annotations =
                    annotations(owner, injection, parameterCursors);
            Configuration configuration = configuration(annotations);
            result.add(new ExternalInjectionPointRecord(injection.kind(), injection.targetType(),
                    injection.qualifiers(), injection.fieldName(), annotations.stream()
                            .map(value -> value.name().toString()).distinct().sorted().toList(),
                    configuration.key(), configuration.defaultValue(), configuration.required()));
        }
        return List.copyOf(result);
    }

    private static Collection<AnnotationInstance> annotations(
            ClassInfo owner, InjectionPointRecord injection,
            Map<String, Integer> parameterCursors) {
        if (owner == null) return List.of();
        if ("FIELD".equals(injection.kind())) {
            FieldInfo field = owner.field(injection.fieldName());
            return field == null ? List.of() : field.annotations();
        }
        List<AnnotationInstance> result = new ArrayList<>();
        for (MethodInfo method : owner.methods()) {
            if (!method.name().equals(injection.fieldName())) continue;
            int start = parameterCursors.getOrDefault(method.name(), 0);
            for (int parameter = start; parameter < method.parametersCount(); parameter++) {
                String type = method.parameterType(parameter).name() == null
                        ? method.parameterType(parameter).toString()
                        : method.parameterType(parameter).name().toString();
                if (!type.equals(injection.targetType())) continue;
                int selected = parameter;
                result.addAll(method.annotations().stream().filter(annotation -> {
                    AnnotationTarget target = annotation.target();
                    return target != null && (target.kind() == AnnotationTarget.Kind.METHOD
                            || (target.kind() == AnnotationTarget.Kind.METHOD_PARAMETER
                            && target.asMethodParameter().position() == selected));
                }).toList());
                parameterCursors.put(method.name(), parameter + 1);
                return result;
            }
        }
        return result;
    }

    private static Configuration configuration(Collection<AnnotationInstance> annotations) {
        for (AnnotationInstance annotation : annotations) {
            String name = annotation.name().toString();
            if (CONFIG_PROPERTY.equals(name)) {
                String key = stringValue(annotation.value("name"));
                String defaultValue = stringValue(annotation.value("defaultValue"));
                boolean configuredDefault = defaultValue != null && !defaultValue.isBlank()
                        && !defaultValue.toLowerCase().contains("unconfigureddvalue");
                return new Configuration(key, configuredDefault ? defaultValue : null,
                        !configuredDefault);
            }
            if (SPRING_VALUE.equals(name)) {
                String expression = stringValue(annotation.value());
                return springConfiguration(expression);
            }
        }
        return Configuration.NONE;
    }

    private static Configuration springConfiguration(String expression) {
        if (expression == null || !expression.startsWith("${") || !expression.endsWith("}")) {
            return Configuration.NONE;
        }
        String body = expression.substring(2, expression.length() - 1);
        int separator = body.indexOf(':');
        return separator < 0
                ? new Configuration(body, null, true)
                : new Configuration(body.substring(0, separator), body.substring(separator + 1), false);
    }

    private static String stringValue(AnnotationValue value) {
        if (value == null) return null;
        try {
            return value.asString();
        } catch (IllegalArgumentException ignored) {
            return value.toString();
        }
    }

    private static Map<Integer, String> reverse(Map<String, Integer> names) {
        Map<Integer, String> result = new HashMap<>();
        names.forEach((name, id) -> result.put(id, name));
        return result;
    }

    private static Map<String, Path> locateOrigins(Set<String> classNames, List<Path> jars) {
        Map<String, Path> result = new LinkedHashMap<>();
        Set<String> entries = new HashSet<>();
        for (String className : classNames) {
            entries.add(className.replace('.', '/') + ".class");
        }
        for (Path jar : jars) {
            if (result.size() == classNames.size()) break;
            try (JarFile file = new JarFile(jar.toFile())) {
                var enumeration = file.entries();
                while (enumeration.hasMoreElements()) {
                    JarEntry entry = enumeration.nextElement();
                    if (!entries.contains(entry.getName())) continue;
                    String className = entry.getName().substring(0, entry.getName().length() - 6)
                            .replace('/', '.');
                    result.putIfAbsent(className, jar);
                }
            } catch (IOException ignored) {
                // Dependency indexing already reports missing/unreadable JARs as degraded.
            }
        }
        return result;
    }

    private static String coordinates(Path jar) {
        if (jar == null) return null;
        List<String> parts = new ArrayList<>();
        for (Path part : jar.toAbsolutePath().normalize()) parts.add(part.toString());
        int gradle = parts.indexOf("files-2.1");
        if (gradle >= 0 && gradle + 3 < parts.size()) {
            return parts.get(gradle + 1) + ':' + parts.get(gradle + 2) + ':'
                    + parts.get(gradle + 3);
        }
        int repository = parts.lastIndexOf("repository");
        if (repository >= 0 && parts.size() >= repository + 4) {
            int artifactIndex = parts.size() - 3;
            String group = String.join(".", parts.subList(repository + 1, artifactIndex));
            return group + ':' + parts.get(artifactIndex) + ':' + parts.get(artifactIndex + 1);
        }
        return jar.getFileName().toString();
    }

    private record FrameworkResolution(
            String framework, BeanResolver.ResolutionResult result) {}

    private record Configuration(String key, String defaultValue, boolean required) {
        private static final Configuration NONE = new Configuration(null, null, false);
    }
}
