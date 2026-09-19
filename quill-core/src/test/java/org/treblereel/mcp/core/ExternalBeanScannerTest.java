package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalBeanScannerTest {

    @TempDir Path tempDir;

    @Test
    void discoversDependencyBeanArtifactAndRequiredConfiguration() throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve("src"));
        source(sources, "jakarta/inject/Inject.java", """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD})
                public @interface Inject {}
                """);
        source(sources, "jakarta/inject/Qualifier.java", """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """);
        source(sources, "jakarta/enterprise/context/ApplicationScoped.java", """
                package jakarta.enterprise.context;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface ApplicationScoped {}
                """);
        source(sources,
                "org/eclipse/microprofile/config/inject/ConfigProperty.java", """
                package org.eclipse.microprofile.config.inject;
                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;
                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface ConfigProperty {
                    String name();
                    String defaultValue() default "";
                }
                """);
        source(sources, "io/casehub/connectors/TwilioSmsConnector.java", """
                package io.casehub.connectors;
                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.inject.Inject;
                import org.eclipse.microprofile.config.inject.ConfigProperty;
                @ApplicationScoped
                public class TwilioSmsConnector {
                    @Inject
                    @ConfigProperty(name = "casehub.connectors.twilio.account-sid", defaultValue = "")
                    String accountSid;
                }
                """);

        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        List<String> sourceFiles;
        try (var walk = Files.walk(sources)) {
            sourceFiles = walk.filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString).toList();
        }
        var compilerArgs = new java.util.ArrayList<String>();
        compilerArgs.add("-d");
        compilerArgs.add(classes.toString());
        compilerArgs.addAll(sourceFiles);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, compilerArgs.toArray(String[]::new)));

        Path jar = tempDir.resolve("repository/io/casehub/connectors/1.0/connectors-1.0.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String entry = classes.relativize(file).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(entry));
                Files.copy(file, output);
                output.closeEntry();
            }
        }

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

        var beans = ExternalBeanScanner.scan(indexer.complete(), List.of(jar));
        var bean = beans.stream().filter(value ->
                        value.className().equals("io.casehub.connectors.TwilioSmsConnector"))
                .findFirst().orElseThrow();
        assertEquals("cdi", bean.framework());
        assertEquals("io.casehub:connectors:1.0", bean.artifact());
        assertEquals(jar.toString(), bean.jarPath());
        assertEquals(1, bean.injectionPoints().size());
        var injection = bean.injectionPoints().getFirst();
        assertEquals("casehub.connectors.twilio.account-sid", injection.configurationKey());
        assertTrue(injection.configurationRequired(),
                "An empty ConfigProperty default does not satisfy Quarkus configuration");
    }

    private static void source(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
