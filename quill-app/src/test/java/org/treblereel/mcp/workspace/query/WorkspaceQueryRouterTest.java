package org.treblereel.mcp.workspace.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;

class WorkspaceQueryRouterTest {

    @TempDir Path workspace;

    @Test
    void resolvesADeclaredDependencyOntoItsWorkspaceProvider() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        repository("provider", "provider-api", "");
        repository("consumer", "consumer", """
                <dependencies><dependency><groupId>org.acme</groupId>
                  <artifactId>provider-api</artifactId><version>1</version>
                  <scope>test</scope></dependency></dependencies>
                """);

        WorkspaceRoute route = new WorkspaceQueryRouter(new WorkspaceProjectScope(workspace))
                .resolveDependency("consumer", ".", "org.acme:provider-api:1");

        assertEquals("resolved", route.status());
        assertTrue(route.resolved());
        WorkspaceHop hop = route.candidates().getFirst();
        assertEquals("consumer", hop.fromRepository());
        assertEquals("provider", hop.toRepository());
        assertEquals("org.acme:provider-api", hop.coordinate());
        assertEquals(java.util.Set.of("test"), hop.scopes());
        assertEquals("test", hop.sourceSet());
        assertEquals("declared_dependency", hop.evidence());
        assertEquals("workspace_coordinates", hop.resolution());
    }

    @Test
    void reportsAmbiguousProvidersWithoutGuessing() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        repository("provider-one", "provider-api", "");
        repository("provider-two", "provider-api", "");
        repository("consumer", "consumer", """
                <dependencies><dependency><groupId>org.acme</groupId>
                  <artifactId>provider-api</artifactId><version>1</version>
                </dependency></dependencies>
                """);

        WorkspaceRoute route = new WorkspaceQueryRouter(new WorkspaceProjectScope(workspace))
                .resolveDependency("consumer", ".", "org.acme:provider-api");

        assertEquals("ambiguous", route.status());
        assertFalse(route.complete());
        assertEquals(2, route.candidates().size());
    }

    @Test
    void staysExplicitOutsideWorkspaceMode() {
        WorkspaceRoute route = new WorkspaceQueryRouter(null)
                .resolveDependency("consumer", ".", "org.acme:provider-api");

        assertEquals("workspace_mode_required", route.status());
        assertFalse(route.resolved());
    }

    private void repository(String name, String artifact, String extra) throws Exception {
        Path root = Files.createDirectories(workspace.resolve(name));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>%s</artifactId><version>1</version>
                  %s
                </project>
                """.formatted(artifact, extra));
    }
}
