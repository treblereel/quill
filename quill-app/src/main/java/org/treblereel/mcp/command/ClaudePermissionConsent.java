package org.treblereel.mcp.command;

import org.treblereel.mcp.index.ClaudeSettingsInstaller;

import java.nio.file.Path;
import java.util.Collection;
import org.treblereel.mcp.mcp.server.ReadOnlyToolNames;

/** Initialization grants current read-only tools locally unless explicitly opted out. */
final class ClaudePermissionConsent {
    private ClaudePermissionConsent() {}

    static void configure(Boolean requested, Collection<Path> targets) {
        if (Boolean.FALSE.equals(requested)) return;
        var rules = java.util.List.of(ReadOnlyToolNames.SERVER_RULE);
        var pending = targets.stream().distinct()
                .filter(target -> !ClaudeSettingsInstaller.areToolsAllowed(target, rules)).toList();
        if (pending.isEmpty()) return;
        int installed = 0;
        for (Path target : pending) {
            if (ClaudeSettingsInstaller.allowTools(target, rules)) installed++;
            else System.err.println("[quill] Warning: could not grant Quill tool permissions in "
                    + target + "; existing settings were preserved.");
        }
        System.out.println("[quill] Quill tool permissions (all read-only) configured in " + installed
                + "/" + pending.size() + " local project(s). Claude workspace trust and "
                + "explicit ask/deny policies still apply.");
    }
}
