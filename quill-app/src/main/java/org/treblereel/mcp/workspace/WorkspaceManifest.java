package org.treblereel.mcp.workspace;

import java.nio.file.Path;
import java.util.List;

/** Persistent, rebuildable configuration for a federated Quill workspace. */
public record WorkspaceManifest(
        int formatVersion,
        Path root,
        int discoveryDepth,
        List<String> excludes,
        String createdAt) {

    public static final int CURRENT_FORMAT = 1;

    public WorkspaceManifest {
        root = root.toAbsolutePath().normalize();
        excludes = List.copyOf(excludes);
    }
}
