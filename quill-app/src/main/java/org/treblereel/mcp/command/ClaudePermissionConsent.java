package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.Collection;
import org.treblereel.mcp.mcp.ReadOnlyToolNames;

/** Initialization grants current read-only tools locally unless explicitly opted out. */
final class ClaudePermissionConsent {
    private ClaudePermissionConsent() {}

    static void configure(Boolean requested, Collection<Path> targets) {
        if (Boolean.FALSE.equals(requested)) return;
        var rules = ReadOnlyToolNames.all();
        var pending = targets.stream().distinct()
                .filter(target -> !ClaudeSettingsInstaller.areToolsAllowed(target, rules)).toList();
        if (pending.isEmpty()) return;
        int installed = 0;
        for (Path target : pending) {
            if (ClaudeSettingsInstaller.allowTools(target, rules)) installed++;
            else System.err.println("[quill] Warning: could not grant Quill tool permissions in "
                    + target + "; existing settings were preserved.");
        }
        System.out.println("[quill] Quill read-only tool permissions configured in " + installed
                + "/" + pending.size() + " local project(s). Claude workspace trust and "
                + "explicit ask/deny policies still apply.");
    }
}
