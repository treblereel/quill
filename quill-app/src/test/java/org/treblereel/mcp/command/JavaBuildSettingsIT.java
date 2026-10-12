package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaBuildSettingsIT {
    @TempDir Path root;
    void write(String path, String text) throws Exception { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); }
    Properties settings(String path) throws Exception {
        var values = new Properties(); try (var reader = Files.newBufferedReader(root.resolve(path))) { values.load(reader); } return values;
    }
    void build(List<String> arguments) throws Exception {
        Path log = root.resolve("build-output.log");
        Process process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        assertTrue(finished, "Build timed out: " + Files.readString(log));
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
    void buildFails(List<String> arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("failed-build.log").toFile()).start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        assertTrue(finished); assertNotEquals(0, process.exitValue());
    }
    Path processor() throws Exception {
        write("processor/src/proc/CaptureProcessor.java", """
            package proc;
            @javax.annotation.processing.SupportedAnnotationTypes("*")
            @javax.annotation.processing.SupportedOptions("message")
            public class CaptureProcessor extends javax.annotation.processing.AbstractProcessor {
                boolean generated;
                public javax.lang.model.SourceVersion getSupportedSourceVersion() { return javax.lang.model.SourceVersion.latestSupported(); }
                public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations, javax.annotation.processing.RoundEnvironment round) {
                    if (!generated && !round.processingOver()) { generated = true;
                        try (var out = processingEnv.getFiler().createSourceFile("generated.Value").openWriter()) {
                            out.write("package generated; public class Value { public static int value() { return 1; } }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                    } return false;
                }
            }
            """);
        Path classes = Files.createDirectories(root.resolve("processor/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", classes.toString(), root.resolve("processor/src/proc/CaptureProcessor.java").toString()));
        Path jar = root.resolve("processor.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("proc/CaptureProcessor.class")); Files.copy(classes.resolve("proc/CaptureProcessor.class"), out); out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor")); out.write("proc.CaptureProcessor\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
        return jar;
    }
    @Test void mavenUsesSessionProfilesToolchainAndSeparateTestSettings() throws Exception {
        Path jar = processor();
        String maven = Path.of(System.getProperty("maven.home"), "bin/mvn").toString();
        build(List.of(maven, "-q", "install:install-file", "-Dfile=" + jar, "-DgroupId=quill.probe", "-DartifactId=java-capture-processor", "-Dversion=1", "-Dpackaging=jar", "-DgeneratePom=true"));
        Path jdk = Path.of(System.getProperty("java.home"));
        String requestedVersion = System.getProperty("java.specification.version");
        write("toolchains.xml", "<toolchains><toolchain><type>jdk</type><provides><version>" + requestedVersion + "</version></provides><configuration><jdkHome>" + jdk + "</jdkHome></configuration></toolchain></toolchains>");
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId><artifactId>capture</artifactId><version>1</version>
            <properties><java.main.release>11</java.main.release></properties>
            <profiles><profile><id>compiler-settings</id><properties><java.main.release>17</java.main.release></properties></profile></profiles>
            <build><directory>${project.basedir}/out</directory><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>
            <configuration><release>${java.main.release}</release><testRelease>21</testRelease><jdkToolchain><version>%s</version></jdkToolchain>
            <compilerArgs><arg>-Amessage=value with spaces</arg></compilerArgs>
            <generatedSourcesDirectory>${project.basedir}/out/generated-main</generatedSourcesDirectory>
            <generatedTestSourcesDirectory>${project.basedir}/out/generated-test</generatedTestSourcesDirectory>
            <annotationProcessors><processor>proc.CaptureProcessor</processor></annotationProcessors>
            <annotationProcessorPaths><path><groupId>quill.probe</groupId><artifactId>java-capture-processor</artifactId><version>1</version></path></annotationProcessorPaths>
            </configuration></plugin></plugins></build></project>
            """.formatted(requestedVersion));
        write("src/main/java/probe/Call.java", "package probe; public class Call { public int call() { return generated.Value.value(); } }");
        write("src/test/java/probe/Check.java", "package probe; public class Check { public int call() { return new Call().call(); } }");
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        String extension = repo.resolve("quill-maven-extension/target/quill-maven-extension-" + BuildIntegrationInstaller.mavenExtensionVersion() + ".jar").toString();
        build(List.of(maven, "-q", "test-compile", "-Pcompiler-settings", "-t", root.resolve("toolchains.xml").toString(), "-Dmaven.ext.class.path=" + extension));
        var main = settings("target/quill-java-main.properties"); var test = settings("target/quill-java-test.properties");
        assertEquals("17", main.getProperty("release")); assertEquals("21", test.getProperty("release"));
        assertEquals("1", main.getProperty("compilerSettingsProtocol"));
        assertEquals("supported", BuildIntegrationCapabilities.inspect(root, Files.isRegularFile(root.resolve("pom.xml"))
                ? org.treblereel.mcp.core.BuildSystem.MAVEN : org.treblereel.mcp.core.BuildSystem.GRADLE).path("status").asText());
        assertEquals("toolchain", main.getProperty("jdkSelection")); assertEquals(jdk.toRealPath().toString(), Path.of(main.getProperty("jdkHome")).toRealPath().toString());
        assertEquals("compiler-settings", main.getProperty("activeProfile.0"));
        assertEquals("-Amessage=value with spaces", main.getProperty("argument.0"));
        assertEquals("proc.CaptureProcessor", main.getProperty("processor.0"));
        assertTrue(Files.isRegularFile(root.resolve("out/generated-main/generated/Value.java")));
        assertTrue(Files.isRegularFile(root.resolve("out/generated-test/generated/Value.java")));
        assertEquals("captured", JavaCompilerSettings.read(root, "main", root.resolve("target/quill-java-main.properties")).path("status").asText());
        String captured = Files.readString(root.resolve("target/quill-java-main.properties"));
        var modified = Files.getLastModifiedTime(root.resolve("pom.xml"));
        Files.writeString(root.resolve("pom.xml"), Files.readString(root.resolve("pom.xml")).replace("<java.main.release>17", "<java.main.release>18"));
        Files.setLastModifiedTime(root.resolve("pom.xml"), modified);
        assertEquals("stale", JavaCompilerSettings.read(root, "main", root.resolve("target/quill-java-main.properties")).path("status").asText());
        write("src/main/java/probe/Call.java", "not valid java");
        buildFails(List.of(maven, "-q", "compile", "-Pcompiler-settings", "-t", root.resolve("toolchains.xml").toString(), "-Dmaven.ext.class.path=" + extension));
        assertEquals(captured, Files.readString(root.resolve("target/quill-java-main.properties")));
    }
    @Test void gradleCapturesCustomSourceSetToolchainProcessorAndTaskOverrides() throws Exception {
        processor();
        write("settings.gradle.kts", "rootProject.name = \"java-capture\"\n");
        write("build.gradle.kts", """
            plugins { java }
            layout.buildDirectory.set(file("out"))
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(%s)) } }
            sourceSets.create("integrationTest")
            dependencies { annotationProcessor(files("processor.jar")); testAnnotationProcessor(files("processor.jar")) }
            tasks.withType<JavaCompile>().configureEach {
                options.release.set(17)
                options.compilerArgs.add("-Amessage=value with spaces")
                options.generatedSourceOutputDirectory.set(layout.buildDirectory.dir("generated/" + name))
            }
            tasks.named<JavaCompile>("compileTestJava") { options.release.set(21) }
            """.formatted(System.getProperty("java.specification.version")));
        write("src/main/java/probe/Call.java", "package probe; public class Call { public int call() { return generated.Value.value(); } }");
        write("src/test/java/probe/Check.java", "package probe; public class Check { public int call() { return new Call().call(); } }");
        write("src/integrationTest/java/probe/Custom.java", "package probe; public class Custom { public int call() { return 1; } }");
        assertEquals(BuildIntegrationInstaller.Result.INSTALLED, BuildIntegrationInstaller.install(root));
        build(List.of("gradle", "compileJava", "compileTestJava", "compileIntegrationTestJava", "--no-daemon", "--quiet"));
        var main = settings("build/quill-java-main.properties"); var test = settings("build/quill-java-test.properties");
        var custom = settings("build/quill-java-integrationTest.properties");
        assertEquals("17", main.getProperty("release")); assertEquals("21", test.getProperty("release"));
        assertEquals("1", main.getProperty("compilerSettingsProtocol"));
        assertEquals("supported", BuildIntegrationCapabilities.inspect(root, Files.isRegularFile(root.resolve("pom.xml"))
                ? org.treblereel.mcp.core.BuildSystem.MAVEN : org.treblereel.mcp.core.BuildSystem.GRADLE).path("status").asText());
        assertEquals("integrationTest", custom.getProperty("sourceSet")); assertEquals("17", custom.getProperty("release"));
        assertEquals("successful_task", main.getProperty("evidence")); assertEquals("task_java_compiler", main.getProperty("jdkSelection"));
        assertEquals("1", main.getProperty("processorPath.count")); assertTrue(main.getProperty("processorPath.0").endsWith("processor.jar"));
        assertTrue(Files.exists(root.resolve("out/generated/compileJava/generated/Value.java")));
        assertEquals("-Amessage=value with spaces", main.getProperty("argument.0"));
        assertEquals("captured", JavaCompilerSettings.read(root, "main", root.resolve("build/quill-java-main.properties")).path("status").asText());
        var files = JavaCompilerSettings.inspect(List.of(root), org.treblereel.mcp.core.BuildSystem.GRADLE);
        assertEquals(3, files.size());
        String captured = Files.readString(root.resolve("build/quill-java-main.properties"));
        write("src/main/java/probe/Call.java", "not valid java");
        buildFails(List.of("gradle", "compileJava", "--no-daemon", "--quiet"));
        assertEquals(captured, Files.readString(root.resolve("build/quill-java-main.properties")));
        BuildIntegrationInstaller.uninstall(root); assertFalse(Files.exists(root.resolve(".quill/java-capture.gradle")));
    }
    @Test void gradleDiscoveryRecordsConfigurationWithoutClaimingCompilerSelection() throws Exception {
        write("settings.gradle", "rootProject.name = 'java-discovery'\n");
        write("build.gradle", "plugins { id 'java' }\ntasks.withType(JavaCompile).configureEach { options.release = 17 }\n");
        var discovery = org.treblereel.mcp.core.GradleProjectDiscovery.discoverAndWriteClasspath(root);
        assertTrue(discovery.complete());
        var main = settings("build/quill-java-main.properties");
        assertEquals("17", main.getProperty("release")); assertEquals("configured_task", main.getProperty("evidence"));
        assertEquals("not_executed", main.getProperty("jdkSelection")); assertNull(main.getProperty("jdkHome"));
        assertFalse(Files.exists(root.resolve("build/classes")));
    }
}
