package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompiledOutputInspectorTest {

    @TempDir Path root;

    @Test
    void sharedOutputSymlinkCannotProveWorkingCopyFreshness() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path source = root.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n");
        Path external = Files.createTempDirectory("quill-shared-output");
        try {
            Files.write(external.resolve("App.class"), new byte[]{1});
            Files.createDirectories(root.resolve("target"));
            Files.createSymbolicLink(root.resolve("target/classes"), external);
            assertEquals(CompiledOutputInspector.State.OWNERSHIP_UNKNOWN,
                    CompiledOutputInspector.inspect(root).state());
        } finally {
            Files.deleteIfExists(root.resolve("target/classes"));
            Files.deleteIfExists(external.resolve("App.class"));
            Files.deleteIfExists(external);
        }
    }

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
