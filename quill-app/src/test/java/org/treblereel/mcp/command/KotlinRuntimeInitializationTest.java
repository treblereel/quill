package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.treblereel.mcp.kotlin.KotlinRuntime;

@ResourceLock("SYSTEM_PROPERTIES")
class KotlinRuntimeInitializationTest {
    @TempDir Path temporary;
    Path project;
    String previousCache, previousLocal, previousOffline;

    @BeforeEach void setup() throws Exception {
        previousCache = System.getProperty("quill.kotlin.cache");
        previousLocal = System.getProperty("quill.kotlin.localRepository");
        previousOffline = System.getProperty("quill.kotlin.offline");
        System.setProperty("quill.kotlin.cache", temporary.resolve("runtime").toString());
        System.setProperty("quill.kotlin.localRepository", temporary.resolve("empty-repository").toString());
        System.setProperty("quill.kotlin.offline", "true");
        project = temporary.resolve("project"); Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>probe</groupId><artifactId>probe</artifactId><version>1</version></project>");
        Path source = project.resolve("src/main/java/probe/Main.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, "package probe; public class Main {}");
        Files.createDirectories(project.resolve("target/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", project.resolve("target/classes").toString(), source.toString()));
        Files.writeString(project.resolve("target/quill-classpath.txt"), "");
    }

    @AfterEach void restore() {
        restore("quill.kotlin.cache", previousCache);
        restore("quill.kotlin.localRepository", previousLocal);
        restore("quill.kotlin.offline", previousOffline);
    }

    private void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    private void kotlinSource() throws Exception {
        Path source = project.resolve("src/main/kotlin/New.kt"); Files.createDirectories(source.getParent());
        Files.writeString(source, "fun newFunction() = 1");
    }

    @Test void javaOnlyInitializationDoesNotInstallCompiler() {
        assertTrue(ProjectInitializer.initializeDetailed(project, false).successful());
        assertFalse(Files.exists(KotlinRuntime.cacheRoot()));
    }

    @Test void indexOnlyNeverInstallsCompilerEvenWithNewKotlinSources() throws Exception {
        kotlinSource();
        assertTrue(ProjectInitializer.initializeDetailed(project, true).successful());
        assertFalse(Files.exists(KotlinRuntime.cacheRoot()));
    }

    @Test void newKotlinSourceTriggersPreparationAndFailurePreservesIndex() throws Exception {
        kotlinSource();
        var result = ProjectInitializer.initializeDetailed(project, false);
        assertTrue(result.successful(), result.diagnostic());
        assertTrue(result.phaseMillis().containsKey("kotlin_runtime"));
        assertTrue(Files.exists(KotlinRuntime.cacheRoot()));
        assertTrue(KotlinRuntime.available().isEmpty());
        assertNotNull(ProjectIndexStore.findDbForHead(project));
    }
}
