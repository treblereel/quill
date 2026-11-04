package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.ResolutionReason;
import org.treblereel.mcp.model.ResolutionStatus;

class InheritedInjectionAndSyntheticBeansTest {

    @TempDir Path tempDir;

    private static final Map<String, String> CDI_STUBS = Map.of(
            "jakarta/inject/Inject.java", """
                    package jakarta.inject;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD})
                    public @interface Inject {}
                    """,
            "jakarta/inject/Qualifier.java", """
                    package jakarta.inject;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.ANNOTATION_TYPE)
                    public @interface Qualifier {}
                    """,
            "jakarta/enterprise/context/ApplicationScoped.java", """
                    package jakarta.enterprise.context;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.TYPE)
                    public @interface ApplicationScoped {}
                    """,
            "jakarta/enterprise/event/Event.java", """
                    package jakarta.enterprise.event;
                    public interface Event<T> { void fire(T event); }
                    """);

    @Test
    void cdiBeanInheritsSuperclassFieldsAndInitializersExceptOverriddenOnes() throws Exception {
        Map<String, String> sources = new java.util.LinkedHashMap<>(CDI_STUBS);
        sources.put("app/Bus.java", "package app; @jakarta.enterprise.context.ApplicationScoped public class Bus {}");
        sources.put("app/Base.java", """
                package app;
                import jakarta.inject.Inject;
                public abstract class Base {
                    @Inject Bus bus;
                    @Inject static Bus ignoredStatic;
                    @Inject void init(Bus bus) {}
                    @Inject void replaced(Bus bus) {}
                    @Inject private void hidden(Bus bus) {}
                }
                """);
        sources.put("app/Child.java", """
                package app;
                @jakarta.enterprise.context.ApplicationScoped
                public class Child extends Base {
                    @Override void replaced(Bus bus) {}
                    private void hidden(Bus bus) {}
                }
                """);

        var result = BeanResolver.resolve(index(sources));
        List<String> members = membersOf(result, "app.Child");

        assertTrue(members.contains("Base.bus"), members.toString());
        assertTrue(members.contains("Base.init"), members.toString());
        assertTrue(members.contains("Base.hidden"),
                "A private subclass method does not override a private superclass initializer: " + members);
        assertFalse(members.contains("Base.replaced"),
                "An overriding method without @Inject is not an initializer: " + members);
        assertFalse(members.contains("Base.ignoredStatic"), members.toString());
        InjectionPointRecord bus = point(result, "app.Child", "Base.bus");
        assertEquals(ResolutionStatus.RESOLVED, bus.resolutionStatus());
    }

    @Test
    void configMappingsBecomeDependentSyntheticBeansExceptBuildTimeRoots() throws Exception {
        Map<String, String> sources = new java.util.LinkedHashMap<>(CDI_STUBS);
        sources.put("io/smallrye/config/ConfigMapping.java", """
                package io.smallrye.config;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface ConfigMapping { String prefix() default ""; }
                """);
        sources.put("io/quarkus/runtime/annotations/ConfigPhase.java", """
                package io.quarkus.runtime.annotations;
                public enum ConfigPhase { BUILD_TIME, BUILD_AND_RUN_TIME_FIXED, RUN_TIME }
                """);
        sources.put("io/quarkus/runtime/annotations/ConfigRoot.java", """
                package io.quarkus.runtime.annotations;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface ConfigRoot { ConfigPhase phase() default ConfigPhase.BUILD_TIME; }
                """);
        sources.put("app/AppConfig.java", """
                package app;
                @io.smallrye.config.ConfigMapping(prefix = "app")
                public interface AppConfig { String name(); }
                """);
        sources.put("app/RuntimeRoot.java", """
                package app;
                import io.quarkus.runtime.annotations.*;
                @io.smallrye.config.ConfigMapping(prefix = "quarkus.runtime")
                @ConfigRoot(phase = ConfigPhase.RUN_TIME)
                public interface RuntimeRoot { String mode(); }
                """);
        sources.put("app/BuildRoot.java", """
                package app;
                @io.smallrye.config.ConfigMapping(prefix = "quarkus.build")
                @io.quarkus.runtime.annotations.ConfigRoot
                public interface BuildRoot { String mode(); }
                """);
        sources.put("app/User.java", """
                package app;
                import jakarta.inject.Inject;
                @jakarta.enterprise.context.ApplicationScoped
                public class User {
                    @Inject AppConfig config;
                    @Inject jakarta.enterprise.event.Event<String> events;
                }
                """);

        var withoutIntegration = BeanResolver.resolve(index(sources));
        assertTrue(withoutIntegration.beans().stream().noneMatch(bean -> "SYNTHETIC".equals(bean.kind())));
        sources.put("io/quarkus/arc/runtime/ConfigMappingCreator.java", """
                package io.quarkus.arc.runtime;
                public class ConfigMappingCreator {}
                """);
        var result = BeanResolver.resolve(index(sources));
        Map<Integer, String> classNames = classNames(result);
        List<BeanRecord> synthetic = result.beans().stream()
                .filter(bean -> "SYNTHETIC".equals(bean.kind())).toList();
        List<String> syntheticClasses = synthetic.stream()
                .map(bean -> classNames.get(bean.classId())).sorted().toList();

        assertEquals(List.of("app.AppConfig", "app.RuntimeRoot"), syntheticClasses);
        BeanRecord appConfig = synthetic.stream()
                .filter(bean -> classNames.get(bean.classId()).equals("app.AppConfig"))
                .findFirst().orElseThrow();
        assertEquals("@Dependent", appConfig.scope());
        assertEquals(BeanResolver.CONFIG_MAPPING_CREATOR, appConfig.memberName());
        assertEquals(appConfig.id(), point(result, "app.User", "config").resolvedBeanId());

        InjectionPointRecord events = point(result, "app.User", "events");
        assertEquals(ResolutionStatus.RESOLVED, events.resolutionStatus());
        assertEquals(ResolutionReason.BUILTIN_BEAN, events.resolutionReason());
        assertNull(events.resolvedBeanId());
    }

    @Test
    void springBeanInheritsAutowiredMembersExceptOverriddenMethods() throws Exception {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("org/springframework/stereotype/Component.java", """
                package org.springframework.stereotype;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Component {}
                """);
        sources.put("org/springframework/beans/factory/annotation/Autowired.java", """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD})
                public @interface Autowired {}
                """);
        sources.put("app/Repo.java", "package app; @org.springframework.stereotype.Component public class Repo {}");
        sources.put("app/Bus.java", "package app; @org.springframework.stereotype.Component public class Bus {}");
        sources.put("app/Base.java", """
                package app;
                import org.springframework.beans.factory.annotation.Autowired;
                public abstract class Base {
                    @Autowired Repo repo;
                    @Autowired void setBus(Bus bus) {}
                    @Autowired void replaced(Bus bus) {}
                }
                """);
        sources.put("app/Child.java", """
                package app;
                @org.springframework.stereotype.Component
                public class Child extends Base {
                    @Override void replaced(Bus bus) {}
                }
                """);

        var result = SpringResolver.resolve(index(sources));
        List<String> members = membersOf(result, "app.Child");

        assertTrue(members.contains("Base.repo"), members.toString());
        assertTrue(members.contains("Base.setBus"), members.toString());
        assertFalse(members.contains("Base.replaced"), members.toString());
        assertNotNull(point(result, "app.Child", "Base.repo").resolvedBeanId());
    }

    @Test
    void externalBeanReadsConfigurationOfInheritedInjectionPoint() throws Exception {
        Map<String, String> sources = new java.util.LinkedHashMap<>(CDI_STUBS);
        sources.put("org/eclipse/microprofile/config/inject/ConfigProperty.java", """
                package org.eclipse.microprofile.config.inject;
                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;
                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
                public @interface ConfigProperty {
                    String name() default "";
                    String defaultValue() default "";
                }
                """);
        sources.put("lib/BaseConnector.java", """
                package lib;
                import jakarta.inject.Inject;
                import org.eclipse.microprofile.config.inject.ConfigProperty;
                public abstract class BaseConnector {
                    @Inject @ConfigProperty(name = "lib.endpoint", defaultValue = "http://localhost")
                    String endpoint;
                }
                """);
        sources.put("lib/Connector.java", """
                package lib;
                @jakarta.enterprise.context.ApplicationScoped
                public class Connector extends BaseConnector {}
                """);

        Path jar = jar(sources);
        var beans = ExternalBeanScanner.scan(index(jar), List.of(jar));
        var connector = beans.stream().filter(bean -> bean.className().equals("lib.Connector"))
                .findFirst().orElseThrow();
        var endpoint = connector.injectionPoints().stream()
                .filter(point -> "BaseConnector.endpoint".equals(point.member()))
                .findFirst().orElseThrow(() -> new AssertionError(connector.injectionPoints().toString()));

        assertEquals("lib.endpoint", endpoint.configurationKey());
        assertEquals("http://localhost", endpoint.configurationDefault());
    }

    private static List<String> membersOf(BeanResolver.ResolutionResult result, String className) {
        int beanId = beanId(result, className);
        return result.injectionPoints().stream().filter(point -> point.beanId() == beanId)
                .map(InjectionPointRecord::fieldName).collect(Collectors.toList());
    }

    private static InjectionPointRecord point(BeanResolver.ResolutionResult result, String className,
            String member) {
        int beanId = beanId(result, className);
        return result.injectionPoints().stream()
                .filter(point -> point.beanId() == beanId && member.equals(point.fieldName()))
                .findFirst().orElseThrow(() -> new AssertionError("No injection point " + member));
    }

    private static int beanId(BeanResolver.ResolutionResult result, String className) {
        Integer classId = result.classNameToId().get(className);
        assertNotNull(classId, className);
        return result.beans().stream()
                .filter(bean -> bean.classId() == classId && "CLASS".equals(bean.kind()))
                .findFirst().orElseThrow().id();
    }

    private static Map<Integer, String> classNames(BeanResolver.ResolutionResult result) {
        Map<Integer, String> names = new java.util.HashMap<>();
        result.classNameToId().forEach((name, id) -> names.put(id, name));
        return names;
    }

    private Path compile(Map<String, String> sources) throws Exception {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src-" + System.nanoTime()));
        Path classes = Files.createDirectories(tempDir.resolve("classes-" + System.nanoTime()));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path file = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                args.toArray(String[]::new)));
        return classes;
    }

    private Index index(Map<String, String> sources) throws Exception {
        Path classes = compile(sources);
        Indexer indexer = new Indexer();
        try (var walk = Files.walk(classes)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    indexer.index(input);
                }
            }
        }
        return indexer.complete();
    }

    private Path jar(Map<String, String> sources) throws Exception {
        Path classes = compile(sources);
        Path jar = tempDir.resolve("repository/lib/connectors/1.0/connectors-1.0.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }

    private static Index index(Path jar) throws Exception {
        Indexer indexer = new Indexer();
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream input = file.getInputStream(entry)) {
                    indexer.index(input);
                }
            }
        }
        return indexer.complete();
    }
}
