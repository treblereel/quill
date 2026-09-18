package org.treblereel.mcp.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceCoordinateCatalogTest {

    @TempDir Path workspace;

    @Test
    void mapsCoordinatesToRepositoriesAndReportsCollisions() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(workspace, 1);
        mavenRepository("engine", "io.casehub", "shared-api", "1.0");
        mavenRepository("platform", "io.casehub", "shared-api", "2.0");

        WorkspaceCoordinateCatalog.Result catalog =
                WorkspaceCoordinateCatalog.discover(manifest);

        assertEquals(2, catalog.modules().size());
        assertEquals(2, catalog.modulesByGa().get("io.casehub:shared-api").size());
        assertTrue(catalog.diagnostics().stream().anyMatch(value ->
                value.contains("provided by 2 workspace modules: engine:.@1.0, "
                        + "platform:.@2.0")));
    }

    @Test
    void unsupportedRepositoryIsOutsideTheJavaCoordinateCatalog() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(workspace, 1);
        mavenRepository("engine", "io.casehub", "engine-api", "1.0");
        Path unsupported = Files.createDirectories(workspace.resolve("notes"));
        Files.createDirectories(unsupported.resolve(".git"));

        WorkspaceCoordinateCatalog.Result catalog =
                WorkspaceCoordinateCatalog.discover(manifest);

        assertTrue(catalog.complete(), catalog.diagnostics().toString());
        assertEquals("engine", catalog.modulesByGa().get("io.casehub:engine-api")
                .getFirst().repository());
        assertTrue(catalog.diagnostics().isEmpty());
    }

    @Test
    void cachesCoordinatesUntilNestedBuildMetadataChanges() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(workspace, 1);
        Path root = Files.createDirectories(workspace.resolve("engine"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>io.casehub</groupId><artifactId>engine</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>api</module></modules>
                </project>
                """);
        Path api = Files.createDirectories(root.resolve("api"));
        Path apiPom = api.resolve("pom.xml");
        Files.writeString(apiPom, """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>io.casehub</groupId><artifactId>engine</artifactId>
                    <version>1</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>engine-api</artifactId>
                </project>
                """);

        WorkspaceCoordinateCatalog.Result first = WorkspaceCoordinateCatalog.discover(manifest);
        WorkspaceCoordinateCatalog.Result cached = WorkspaceCoordinateCatalog.discover(manifest);
        assertSame(first, cached);

        Files.writeString(apiPom, Files.readString(apiPom)
                .replace("engine-api", "engine-contract"));
        WorkspaceCoordinateCatalog.Result changed = WorkspaceCoordinateCatalog.discover(manifest);

        assertNotSame(first, changed);
        assertTrue(changed.modulesByGa().containsKey("io.casehub:engine-contract"));
    }

    private void mavenRepository(String name, String group, String artifact, String version)
            throws Exception {
        Path root = Files.createDirectories(workspace.resolve(name));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                </project>
                """.formatted(group, artifact, version));
    }
}
