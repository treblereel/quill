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
            write("build.gradle.kts", initialBuild + "\nkotlin.sourceSets.named(\"integrationTest\") { kotlin.exclude(\"**/Probe.kt\") }\n");
            assertTrue(GradleProjectDiscovery.discoverAndWriteClasspath(root).complete());
            assertEquals("source_not_in_source_set", KotlinSemanticQueries.resolve(db, root,
                    root.resolve("relocated ü/kotlin/Probe.kt"), integration.lastIndexOf("capitalize(")).path("status").asText());
            write("build.gradle.kts", initialBuild + "\nkotlin.sourceSets.named(\"integrationTest\") { "
                    + "kotlin.exclude(org.gradle.api.specs.Spec<org.gradle.api.file.FileTreeElement> { it.name == \"Future.kt\" }) }\n");
            assertTrue(GradleProjectDiscovery.discoverAndWriteClasspath(root).complete());
            assertEquals("resolved", KotlinSemanticQueries.resolve(db, root, root.resolve("relocated ü/kotlin/Probe.kt"), integration.lastIndexOf("capitalize(")).path("status").asText());
            write("relocated ü/kotlin/Future.kt", "invalid excluded Kotlin");
            assertEquals("source_selection_refresh_required", KotlinSemanticQueries.resolve(db, root,
                    root.resolve("relocated ü/kotlin/Probe.kt"), 0).path("status").asText());
            // Refresh the evaluated predicate snapshot after source topology changes.
            org.treblereel.mcp.core.GradleProjectDiscovery.discoverAndWriteClasspath(root);
            assertEquals("resolved", KotlinSemanticQueries.resolve(db, root, root.resolve("relocated ü/kotlin/Probe.kt"), integration.lastIndexOf("capitalize(")).path("status").asText());
            write("override sources ü/Probe.kt", "package extra\nfun pick() = true\nfun run() = pick()");
            write("override sources ü/Ignored.kt", "invalid excluded Kotlin");
            write("override sources ü/Api.java", "package extra; public class Api { public static String pick() { return \"ok\"; } }");
            write("override sources ü/Ignored.java", "invalid excluded Java");
            write("build.gradle.kts", initialBuild + """
                    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIntegrationTestKotlin") {
                        setSource(fileTree("override sources ü") { include("**/*.kt", "**/*.java"); exclude("**/Ignored.kt", "**/Ignored.java") })
                    }
                    """);
            assertTrue(GradleProjectDiscovery.discoverAndWriteClasspath(root).complete());
            db.useHandle(h -> h.execute("INSERT INTO files(project_path,repository_path,kind,origin,lifecycle,module,source_set) "
                    + "VALUES('override sources ü/Probe.kt','override sources ü/Probe.kt','kotlin','source','current','.','integrationTest')"));
            Path override = root.resolve("override sources ü/Probe.kt");
            assertEquals("()Z", KotlinSemanticQueries.resolve(db, root, override, Files.readString(override).lastIndexOf("pick()")).path("descriptor").asText());
            write("override sources ü/New.kt", "package extra\nfun added() = 123");
            write("override sources ü/Probe.kt", "package extra\nfun run() = added()");
            assertEquals("()I", KotlinSemanticQueries.resolve(db, root, override, Files.readString(override).lastIndexOf("added()")).path("descriptor").asText());
            write("override sources ü/Probe.kt", "package extra\nfun run() = Api.pick()");
            var mixed = KotlinSemanticQueries.resolve(db, root, override, Files.readString(override).lastIndexOf("pick()"));
            assertEquals("resolved", mixed.path("status").asText(), mixed.toString());
            assertEquals("extra.Api", mixed.path("owner").asText());
            write("override sources ü/Probe.kt", "package extra\nfun run() = added()");
            Files.delete(root.resolve("override sources ü/New.kt"));
            assertEquals("analysis_error", KotlinSemanticQueries.resolve(db, root, override, 0).path("status").asText());
        } finally {
            KotlinSemanticQueries.closeWarmup(); org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().close();
            if (previousCache == null) System.clearProperty("quill.kotlin.cache"); else System.setProperty("quill.kotlin.cache", previousCache);
            if (previousOffline == null) System.clearProperty("quill.kotlin.offline"); else System.setProperty("quill.kotlin.offline", previousOffline);
        }
    }
}
