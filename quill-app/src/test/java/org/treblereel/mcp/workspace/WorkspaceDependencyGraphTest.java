package org.treblereel.mcp.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDependencyGraphTest {

    @TempDir Path workspace;

    @Test
    void linksConsumerToLocalProviderAndReportsBinaryVersionDrift() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(workspace, 1);
        mavenRepository("provider", "io.casehub", "provider-api", "2.0", "");
        Path consumer = mavenRepository("consumer", "io.casehub", "consumer", "1.0", """
                <dependencies><dependency><groupId>io.casehub</groupId>
                  <artifactId>provider-api</artifactId><version>1.0</version>
                  <scope>compile</scope></dependency></dependencies>
                """);
        Path jar = workspace.resolve("m2/io/casehub/provider-api/1.0/provider-api-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.createFile(jar);
        Path target = Files.createDirectories(consumer.resolve("target"));
        Files.writeString(target.resolve("quill-classpath.txt"), jar.toString());

        WorkspaceDependencyGraph.Result graph = WorkspaceDependencyGraph.discover(manifest);

        assertEquals(1, graph.edges().size());
        WorkspaceDependencyGraph.Edge edge = graph.edges().getFirst();
        assertEquals("consumer", edge.consumerRepository());
        assertEquals("provider", edge.providerRepository());
        assertEquals("io.casehub:provider-api", edge.coordinate());
        assertEquals("2.0", edge.checkoutVersion());
        assertEquals("1.0", edge.resolvedBinaryVersion());
        assertEquals("binary_behind_checkout", edge.status());
        assertTrue(edge.crossRepository());
        assertEquals(java.util.Set.of("compile"), edge.scopes());
    }

    @Test
    void reportsMatchingGradleCacheVersion() throws Exception {
        Path jar = workspace.resolve(
                "gradle/caches/modules-2/files-2.1/io.casehub/provider-api/2.0/hash/provider-api-2.0.jar");
        Files.createDirectories(jar.getParent());
        Files.createFile(jar);

        assertEquals("2.0", WorkspaceDependencyGraph.resolvedVersion(
                java.util.List.of(jar), "io.casehub:provider-api"));
    }

    private Path mavenRepository(String name, String group, String artifact, String version,
            String extra) throws Exception {
        Path root = Files.createDirectories(workspace.resolve(name));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>
                """.formatted(group, artifact, version, extra));
        return root;
    }
}
