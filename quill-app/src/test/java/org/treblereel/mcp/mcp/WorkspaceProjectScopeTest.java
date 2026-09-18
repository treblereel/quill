package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;

class WorkspaceProjectScopeTest {

    @TempDir Path workspace;

    @Test
    void reconcilesAddedAndRemovedRepositoriesAtomically() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        Path engine = repository("engine");
        WorkspaceProjectScope scope = new WorkspaceProjectScope(workspace);

        ProjectScope.Snapshot initial = scope.snapshot();
        assertEquals(java.util.List.of("engine"), names(initial));

        repository("platform");
        ProjectScope.Snapshot added = scope.refresh();
        assertEquals(java.util.List.of("engine", "platform"), names(added));
        assertEquals(java.util.List.of("engine"), names(initial),
                "Published snapshots must remain immutable after reconciliation");

        Files.delete(engine.resolve(".git"));
        ProjectScope.Snapshot removed = scope.refresh();
        assertEquals(java.util.List.of("platform"), names(removed));
        assertTrue(removed.revision() > added.revision());
    }

    @Test
    void respectsDiscoveryDepthAndExcludedDirectories() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        repository("direct");
        Files.createDirectories(workspace.resolve("target/ignored/.git"));
        Files.createDirectories(workspace.resolve("group/nested/.git"));

        WorkspaceProjectScope scope = new WorkspaceProjectScope(workspace);

        assertEquals(java.util.List.of("direct"), names(scope.snapshot()));
    }

    @Test
    void recognizesGitWorktreeMarkerFiles() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        Path worktree = Files.createDirectories(workspace.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: /tmp/example\n");

        WorkspaceProjectScope scope = new WorkspaceProjectScope(workspace);

        assertEquals(java.util.List.of("worktree"), names(scope.snapshot()));
    }

    private Path repository(String relative) throws Exception {
        Path root = Files.createDirectories(workspace.resolve(relative));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        return root;
    }

    private static java.util.List<String> names(ProjectScope.Snapshot snapshot) {
        return snapshot.projects().stream().map(ProjectScope.Project::name).toList();
    }
}
