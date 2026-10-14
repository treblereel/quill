package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleProjectScopeTest {

    @TempDir Path tempDir;

    @Test
    void publishesImmutableSnapshotsAndDeduplicatesNames() throws IOException {
        Path first = createProject(tempDir.resolve("one/shared"));
        Path second = createProject(tempDir.resolve("two/shared"));
        SingleProjectScope scope = new SingleProjectScope();

        scope.register(first);
        ProjectScope.Snapshot beforeSecondRegistration = scope.snapshot();
        scope.register(second);
        ProjectScope.Snapshot current = scope.snapshot();

        assertEquals(1, beforeSecondRegistration.projects().size());
        assertEquals(1, beforeSecondRegistration.revision());
        assertEquals(2, current.revision());
        assertEquals("shared", current.projects().get(0).name());
        assertEquals("shared-1", current.projects().get(1).name());
        assertThrows(UnsupportedOperationException.class,
                () -> current.projects().clear());
    }

    @Test
    void registryBackedByExternalScopeRejectsManualRegistration() {
        ProjectScope scope = () -> new ProjectScope.Snapshot(1, java.util.List.of());
        ProjectRegistry registry = new ProjectRegistry(scope);

        assertThrows(IllegalStateException.class, () -> registry.register(tempDir));
    }

    private static Path createProject(Path root) throws IOException {
        Files.createDirectories(root);
        Files.createFile(root.resolve("pom.xml"));
        return root;
    }

    @Test
    void aliasesRegisterOnceAndIdentityAndAbsolutePathsSelectTheProject() throws Exception {
        Path root = createProject(tempDir.resolve("project"));
        Path alias = tempDir.resolve("alias");
        Files.createSymbolicLink(alias, root);
        SingleProjectScope scope = new SingleProjectScope();
        scope.register(root);
        scope.register(alias);
        assertEquals(1, scope.snapshot().projects().size());
        assertEquals(1, scope.snapshot().revision());
        String id = org.treblereel.mcp.core.ProjectIdentity.resolve(root).projectId();
        assertEquals(1, scope.select(scope.snapshot(), id).size());
        assertEquals(1, scope.select(scope.snapshot(), alias.toString()).size());
        assertEquals(0, scope.select(scope.snapshot(), "unknown").size());
    }

    @Test
    void ambiguousExplicitSelectorDoesNotOpenAnyIndex() throws Exception {
        Path a = createProject(tempDir.resolve("one/app"));
        Path b = createProject(tempDir.resolve("two/App"));
        ProjectScope scope = () -> new ProjectScope.Snapshot(1, java.util.List.of(
                new ProjectScope.Project("app", a), new ProjectScope.Project("App", b)));
        var result = new ProjectRegistry(scope).resolve("app");
        assertEquals(0, result.projects().size());
        org.junit.jupiter.api.Assertions.assertTrue(result.errors().getFirst().startsWith("AMBIGUOUS_PROJECT"));
        org.junit.jupiter.api.Assertions.assertTrue(result.errors().getFirst().contains(a.toRealPath().toString()));
    }
}
