package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.treblereel.mcp.core.KotlinGradleCapture;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.kotlin.KotlinRuntime;

@ResourceLock("SYSTEM_PROPERTIES")
class KotlinCustomSourceSetIT {
    @TempDir Path root;
    private void write(String path, String text) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }

    @Test void actualGradleInputsKeepCustomClasspathSeparateAndPreserveTestFriendVisibility() throws Exception {
        write("settings.gradle.kts", "rootProject.name = \"custom-k2\"");
        write("build.gradle.kts", """
                plugins { kotlin("jvm") version "2.4.21" }
                repositories { mavenCentral() }
                kotlin { jvmToolchain(17) }
                val integrationTest by sourceSets.creating {
                    compileClasspath += sourceSets.main.get().output
                }
                kotlin.sourceSets.named("integrationTest") {
                    kotlin.setSrcDirs(listOf("relocated ü/kotlin"))
                }
                dependencies { "integrationTestImplementation"("org.apache.commons:commons-lang3:3.17.0") }
                tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIntegrationTestKotlin") {
                    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
                """);
        write("src/main/kotlin/Api.kt", "package probe\ninternal fun hidden() = 42\nclass Api");
        String integration = "package custom\nimport org.apache.commons.lang3.StringUtils\nfun run() = StringUtils.capitalize(\"ok\")";
        write("relocated ü/kotlin/Probe.kt", integration);
        String test = "package probe\nfun run() = hidden()";
        write("src/test/kotlin/Test.kt", test);
        write("capture.gradle", KotlinGradleCapture.script());
        Path log = root.resolve("gradle.log");
        Process process = new ProcessBuilder("gradle", "classes", "testClasses", "integrationTestClasses", "--init-script", "capture.gradle", "--quiet")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        assertTrue(finished, "Gradle timed out"); assertEquals(0, process.exitValue(), Files.readString(log));
        assertTrue(GradleProjectDiscovery.discoverAndWriteClasspath(root).complete());
        var db = QuillDatabase.create(root.resolve("index.db"));
        for (String[] context : new String[][]{{"relocated ü/kotlin/Probe.kt", "integrationTest"},
                {"src/test/kotlin/Test.kt", "test"}, {"src/main/kotlin/Api.kt", "main"}}) {
            db.useHandle(h -> h.execute("INSERT INTO files(project_path,repository_path,kind,origin,lifecycle,module,source_set) "
                    + "VALUES(?,?,'kotlin','source','current','.',?)", context[0], context[0], context[1]));
        }
        String previousCache = System.getProperty("quill.kotlin.cache"), previousOffline = System.getProperty("quill.kotlin.offline");
        try {
            System.setProperty("quill.kotlin.cache", root.resolve("runtime").toString());
            System.setProperty("quill.kotlin.offline", "true"); KotlinRuntime.prepare();
            var custom = KotlinSemanticQueries.resolve(db, root, root.resolve("relocated ü/kotlin/Probe.kt"), integration.lastIndexOf("capitalize("));
            assertEquals("resolved", custom.path("status").asText(), custom + "\n" + Files.readString(root.resolve("build/quill-kotlin-integrationTest.properties")));
            assertEquals("org.apache.commons.lang3.StringUtils", custom.path("owner").asText());
            var standardTest = KotlinSemanticQueries.resolve(db, root, root.resolve("src/test/kotlin/Test.kt"), test.lastIndexOf("hidden()"));
            assertEquals("resolved", standardTest.path("status").asText(), standardTest + "\n" + Files.readString(root.resolve("build/quill-kotlin-test.properties")));
            assertEquals("probe.ApiKt", standardTest.path("owner").asText());
            // The dependency is only on integrationTest; main must fail to resolve it.
            write("src/main/kotlin/Api.kt", integration);
            assertEquals("analysis_error", KotlinSemanticQueries.resolve(db, root, root.resolve("src/main/kotlin/Api.kt"), integration.lastIndexOf("capitalize(")).path("status").asText());
            // A broken main source is not part of the custom set's frontend inputs.
            write("src/main/kotlin/Api.kt", "not valid Kotlin");
            assertEquals("resolved", KotlinSemanticQueries.resolve(db, root, root.resolve("relocated ü/kotlin/Probe.kt"), integration.lastIndexOf("capitalize(")).path("status").asText());
            String initialBuild = Files.readString(root.resolve("build.gradle.kts"));
            for (String filter : new String[]{"kotlin.exclude(\"**/Probe.kt\")",
                    "kotlin.exclude(org.gradle.api.specs.Spec<org.gradle.api.file.FileTreeElement> { it.name == \"Future.kt\" })"}) {
                write("build.gradle.kts", initialBuild + "\nkotlin.sourceSets.named(\"integrationTest\") { " + filter + " }\n");
                assertTrue(GradleProjectDiscovery.discoverAndWriteClasspath(root).complete());
                assertEquals("compiler_settings_unavailable", KotlinSemanticQueries.resolve(db, root,
                        root.resolve("relocated ü/kotlin/Probe.kt"), integration.lastIndexOf("capitalize(")).path("status").asText(), filter);
            }
        } finally {
            KotlinSemanticQueries.closeWarmup(); org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().close();
            if (previousCache == null) System.clearProperty("quill.kotlin.cache"); else System.setProperty("quill.kotlin.cache", previousCache);
            if (previousOffline == null) System.clearProperty("quill.kotlin.offline"); else System.setProperty("quill.kotlin.offline", previousOffline);
        }
    }
}
