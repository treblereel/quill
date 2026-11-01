package org.treblereel.mcp.index;

import org.treblereel.mcp.index.CompiledOutputInspector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompiledOutputInspectorTest {

    @TempDir Path root;

    @Test
    void distinguishesMissingFreshAndStaleClasses() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path source = root.resolve("src/main/kotlin/org/acme/App.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme\nclass App\n");

        assertEquals(CompiledOutputInspector.State.MISSING,
                CompiledOutputInspector.inspect(root).state());

        Path compiled = root.resolve("target/classes/org/acme/App.class");
        Files.createDirectories(compiled.getParent());
        Files.write(compiled, new byte[] {1});
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(source, FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(root.resolve("pom.xml"), FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(compiled, FileTime.fromMillis(now));

        assertEquals(CompiledOutputInspector.State.FRESH,
                CompiledOutputInspector.inspect(root).state());

        Files.setLastModifiedTime(source, FileTime.fromMillis(now + 2_000));
        CompiledOutputInspector.Report stale = CompiledOutputInspector.inspect(root);
        assertEquals(CompiledOutputInspector.State.STALE, stale.state());
        assertEquals(java.util.List.of("."), stale.staleModules());
    }

    @Test
    void combinedObservationMatchesLiveSeparateChecksAndDetectsNewOutputs() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        Path source=root.resolve("src/main/java/App.java"),compiled=root.resolve("target/classes/App.class");
        Files.createDirectories(source.getParent());Files.createDirectories(compiled.getParent());
        Files.writeString(source,"class App {}");Files.write(compiled,new byte[]{1});
        var indexed=java.time.Instant.now().plusSeconds(2);
        var first=CompiledOutputInspector.observe(root,indexed);
        assertEquals(CompiledOutputInspector.inspect(root),first.report());
        assertEquals(CompiledOutputInspector.outputsChangedAfter(root,indexed),first.changedAfterIndex());
        Files.write(root.resolve("target/classes/Added.class"),new byte[]{2});
        Files.setLastModifiedTime(root.resolve("target/classes/Added.class"),FileTime.from(indexed.plusSeconds(1)));
        org.junit.jupiter.api.Assertions.assertTrue(CompiledOutputInspector.observe(root,indexed).changedAfterIndex());
    }

    @Test
    void pureParentPomDoesNotRequireCompiledClasses() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>parent</artifactId><version>1</version>
                  <packaging>pom</packaging>
                </project>
                """);

        assertEquals(CompiledOutputInspector.State.NOT_APPLICABLE,
                CompiledOutputInspector.inspect(root).state());
    }

    @Test
    void resourceOnlyMainWithCompiledTestsDoesNotRequireMainBytecode() throws Exception {
        resources(root);
        write(root.resolve("src/test/java/ResourceTest.java"), "class ResourceTest {}");
        write(root.resolve("target/test-classes/ResourceTest.class"), "test bytecode");
        assertEquals(CompiledOutputInspector.State.NOT_APPLICABLE, CompiledOutputInspector.inspect(root).state());
        assertEquals(CompiledOutputInspector.State.NOT_APPLICABLE, CompiledOutputInspector.inspect(root, java.util.List.of(".")).state());
    }

    @Test
    void mixedReactorDoesNotLetResourcesHideMissingJvmOutput() throws Exception {
        write(root.resolve("pom.xml"), "<project><packaging>pom</packaging><modules><module>code</module><module>yaml</module></modules></project>");
        Path code = root.resolve("code"), yaml = root.resolve("yaml");
        resources(yaml);
        write(code.resolve("pom.xml"), "<project/>");
        write(code.resolve("src/main/java/App.java"), "class App {}");
        assertEquals(java.util.List.of("code"), CompiledOutputInspector.inspect(root).staleModules());
        write(code.resolve("target/classes/App.class"), "main bytecode");
        long now = System.currentTimeMillis();
        for (Path p : java.util.List.of(root.resolve("pom.xml"), code.resolve("pom.xml"), code.resolve("src/main/java/App.java")))
            Files.setLastModifiedTime(p, FileTime.fromMillis(now - 2000));
        assertEquals(CompiledOutputInspector.State.FRESH, CompiledOutputInspector.inspect(root).state());
        assertEquals(CompiledOutputInspector.State.NOT_APPLICABLE, CompiledOutputInspector.inspect(root, java.util.List.of("yaml")).state());
        write(yaml.resolve("target/generated-sources/annotations/Generated.java"), "class Generated {}");
        assertEquals(java.util.List.of("yaml"), CompiledOutputInspector.inspect(root).staleModules());
    }

    @Test
    void resourcesDoNotExemptProductionSources() throws Exception {
        for (String path : java.util.List.of("src/main/java/App.java", "src/main/kotlin/App.kt",
                "target/generated-sources/annotations/App.java", "custom/App.java", "src/main/scala/App.scala", "src/main/proto/app.proto")) {
            Path module = root.resolve(Integer.toString(path.hashCode()));
            resources(module);
            write(module.resolve(path), "class App {}");
            assertEquals(CompiledOutputInspector.State.MISSING, CompiledOutputInspector.inspect(module).state(), path);
        }
    }

    @Test
    void externalCustomSourceRootIsNotInferredToBeResourceOnly() throws Exception {
        resources(root);
        write(root.resolve("pom.xml"), "<project><build><sourceDirectory>../external-code</sourceDirectory></build></project>");
        assertEquals(CompiledOutputInspector.State.MISSING, CompiledOutputInspector.inspect(root).state());
    }

    private static void resources(Path module) throws Exception {
        write(module.resolve("pom.xml"), "<project/>");
        write(module.resolve("src/main/resources/plan.yaml"), "steps: []");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    @Test
    void aggregatorPomIsNotTreatedAsAClassProducingModule() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>reactor</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>service</module></modules>
                </project>
                """);
        Path service = Files.createDirectories(root.resolve("service"));
        Files.writeString(service.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.acme</groupId><artifactId>reactor</artifactId>
                    <version>1</version></parent><artifactId>service</artifactId>
                </project>
                """);
        Path source = service.resolve("src/main/java/org/acme/Service.java");
        Path compiled = service.resolve("target/classes/org/acme/Service.class");
        Files.createDirectories(source.getParent());
        Files.createDirectories(compiled.getParent());
        Files.writeString(source, "package org.acme; public final class Service {}\n");
        Files.write(compiled, new byte[] {1});
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(root.resolve("pom.xml"), FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(service.resolve("pom.xml"), FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(source, FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(compiled, FileTime.fromMillis(now));

        assertEquals(CompiledOutputInspector.State.FRESH,
                CompiledOutputInspector.inspect(root).state());

        Files.setLastModifiedTime(root.resolve("pom.xml"), FileTime.fromMillis(now + 2_000));
        CompiledOutputInspector.Report stale = CompiledOutputInspector.inspect(root);
        assertEquals(CompiledOutputInspector.State.STALE, stale.state());
        assertEquals(java.util.List.of("service"), stale.staleModules());
    }
}
