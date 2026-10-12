package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.parallel.ResourceLock("SYSTEM_PROPERTIES")
class KotlinBuildSettingsIT {
    @TempDir Path root;
    void write(String path, String text) throws Exception { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); }
    Properties settings(String path) throws Exception {
        var values = new Properties(); try(var reader = Files.newBufferedReader(root.resolve(path))) { values.load(reader); } return values;
    }
    void build(List<String> arguments) throws Exception {
        Path log = root.resolve("build-output.log");
        Process process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        assertTrue(finished, "Build timed out: " + Files.readString(log));
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
    @Test void gradleKotlinDslCapturesTaskOverridesPluginsAndCustomBuildDirectory() throws Exception {
        write("settings.gradle.kts", "rootProject.name = \"capture\"\n");
        write("build.gradle.kts", """
            plugins { kotlin("jvm") version "2.4.21"; kotlin("plugin.allopen") version "2.4.21" }
            repositories { mavenCentral() }
            layout.buildDirectory.set(file("out"))
            kotlin {
                jvmToolchain(17)
                compilerOptions {
                    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
                    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
                    freeCompilerArgs.add("-Xcontext-parameters")
                }
            }
            tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
                compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            }
            allOpen { annotation("probe.Open") }
            """);
        write("src/main/kotlin/Call.kt", "package probe\nannotation class Open\n@Open class Base { fun pick() = 1 }\nclass Derived: Base()\nfun call() = Derived().pick()\n");
        write("src/test/kotlin/Test.kt", "package probe\nfun check() = Derived()\n");
        assertEquals(BuildIntegrationInstaller.Result.INSTALLED, BuildIntegrationInstaller.install(root));
        build(List.of("gradle", "compileKotlin", "compileTestKotlin", "--no-daemon", "--quiet"));
        var main = settings("build/quill-kotlin-main.properties"); var test = settings("build/quill-kotlin-test.properties");
        assertEquals("2.3", main.getProperty("languageVersion")); assertEquals("2.3", main.getProperty("apiVersion"));
        assertEquals("2.2", test.getProperty("apiVersion")); assertEquals("17", main.getProperty("jvmTarget"));
        assertEquals("2.4.21", main.getProperty("compilerVersion")); assertEquals("-Xcontext-parameters", main.getProperty("argument.0"));
        assertTrue(Integer.parseInt(main.getProperty("pluginClasspath.count")) > 0);
        assertTrue(main.values().stream().anyMatch(value -> value.toString().equals("plugin:org.jetbrains.kotlin.allopen:annotation=probe.Open")), main.toString());
        verifyCapturedPlugin(main);
        assertEquals(BuildIntegrationInstaller.State.INSTALLED, BuildIntegrationInstaller.inspect(root).state());
        Files.delete(root.resolve(".quill/kotlin-capture.gradle"));
        assertEquals(BuildIntegrationInstaller.State.OUTDATED, BuildIntegrationInstaller.inspect(root).state());
        assertEquals(BuildIntegrationInstaller.Result.UPDATED, BuildIntegrationInstaller.install(root));
        BuildIntegrationInstaller.uninstall(root); assertFalse(Files.exists(root.resolve(".quill/kotlin-capture.gradle")));
    }
    @Test void gradleInitializationCapturesLazyTasksWithoutCompilerPluginsOrCompilation() throws Exception {
        write("settings.gradle.kts", "rootProject.name = \"plain-capture\"\ninclude(\":child\")\n");
        write("build.gradle.kts", "plugins { kotlin(\"jvm\") version \"2.4.21\" }\nrepositories { mavenCentral() }\n");
        write("child/build.gradle.kts", "plugins { kotlin(\"jvm\") }\nrepositories { mavenCentral() }\n");
        var discovery = org.treblereel.mcp.core.GradleProjectDiscovery.discoverAndWriteClasspath(root);
        assertTrue(discovery.complete(), discovery.toString());
        var main = settings("build/quill-kotlin-main.properties");
        assertEquals("2.4.21", main.getProperty("compilerVersion"));
        assertEquals("0", main.getProperty("pluginClasspath.count"), "Plain Kotlin JVM must not require user plugin opt-in");
        assertEquals("0", main.getProperty("pluginOption.count"));
        assertEquals("0", settings("child/build/quill-kotlin-main.properties").getProperty("pluginClasspath.count"));
        assertFalse(Files.exists(root.resolve("build/classes")), "Discovery must not compile the project");
    }
    @Test void mavenCapturesExecutionSpecificApiAndPluginOptions() throws Exception {
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>capture</artifactId><version>1</version>
            <dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.4.21</version></dependency></dependencies>
            <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.4.21</version>
            <configuration><languageVersion>2.3</languageVersion><apiVersion>2.3</apiVersion><jvmTarget>17</jvmTarget>
            <sourceDirs><sourceDir>${project.basedir}/src/main/kotlin</sourceDir></sourceDirs>
            <compilerPlugins><plugin>all-open</plugin></compilerPlugins><pluginOptions><option>all-open:annotation=probe.Open</option></pluginOptions></configuration>
            <executions><execution><id>main</id><phase>compile</phase><goals><goal>compile</goal></goals></execution>
            <execution><id>test</id><phase>test-compile</phase><goals><goal>test-compile</goal></goals><configuration><apiVersion>2.2</apiVersion></configuration></execution></executions>
            <dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-allopen</artifactId><version>2.4.21</version></dependency></dependencies>
            </plugin></plugins></build></project>
            """);
        write("src/main/kotlin/Call.kt", "package probe\nannotation class Open\n@Open class Base { fun pick() = 1 }\nclass Derived: Base()\nfun call() = Derived().pick()\n");
        Path repository = Path.of(System.getProperty("user.dir")).getParent();
        String extension = repository.resolve("quill-maven-extension/target/quill-maven-extension-" + BuildIntegrationInstaller.mavenExtensionVersion() + ".jar").toString();
        String maven = Path.of(System.getProperty("maven.home"), "bin/mvn").toString();
        build(List.of(maven, "test-compile", "-q", "-Dmaven.ext.class.path=" + extension));
        var main = settings("target/quill-kotlin-main.properties"); var test = settings("target/quill-kotlin-test.properties");
        assertEquals("2.3", main.getProperty("apiVersion")); assertEquals("2.2", test.getProperty("apiVersion"));
        assertEquals("17", main.getProperty("jvmTarget")); assertEquals("2.4.21", main.getProperty("compilerVersion"));
        assertEquals("plugin:org.jetbrains.kotlin.allopen:annotation=probe.Open", main.getProperty("pluginOption.0"));
        assertTrue(Integer.parseInt(main.getProperty("pluginClasspath.count")) > 0, main.toString());
        verifyCapturedPlugin(main);
    }
    void verifyCapturedPlugin(Properties captured) throws Exception {
        String previousCache = System.getProperty("quill.kotlin.cache"), previousOffline = System.getProperty("quill.kotlin.offline");
        var client = org.treblereel.mcp.kotlin.KotlinWorkerClient.shared();
        try {
            System.setProperty("quill.kotlin.cache", root.resolve("runtime").toString()); System.setProperty("quill.kotlin.offline", "true");
            org.treblereel.mcp.kotlin.KotlinRuntime.prepare();
            Path file = root.resolve("src/main/kotlin/Call.kt");
            var request = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("module", root.toString()).put("source_set", "main").put("file", file.toString())
                .put("offset", Files.readString(file).lastIndexOf("pick()")).put("allow_compiler_plugins", true);
            for (String key : List.of("languageVersion", "apiVersion", "jvmTarget", "compilerVersion"))
                if (captured.containsKey(key)) request.put(key, captured.getProperty(key));
            request.putArray("sources").add(file.toString());
            request.putArray("classpath").add(Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            for (var field : Map.of("plugin_classpath", "pluginClasspath", "plugin_options", "pluginOption", "compiler_arguments", "argument").entrySet()) {
                var values = request.putArray(field.getKey()); int count = Integer.parseInt(captured.getProperty(field.getValue() + ".count", "0"));
                for (int i = 0; i < count; i++) values.add(captured.getProperty(field.getValue() + "." + i));
            }
            var result = client.query(request); assertEquals("resolved", result.path("status").asText(), result.toString());
            assertEquals("()I", result.path("descriptor").asText()); assertEquals("probe.Base", result.path("owner").asText());
        } finally {
            client.close();
            if (previousCache == null) System.clearProperty("quill.kotlin.cache"); else System.setProperty("quill.kotlin.cache", previousCache);
            if (previousOffline == null) System.clearProperty("quill.kotlin.offline"); else System.setProperty("quill.kotlin.offline", previousOffline);
        }
    }
}
