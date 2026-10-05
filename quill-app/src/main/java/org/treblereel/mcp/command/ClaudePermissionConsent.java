package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Function;
import org.treblereel.mcp.mcp.ReadOnlyToolNames;

/** A single, explicit consent step; non-interactive initialization never silently grants access. */
final class ClaudePermissionConsent {
    private ClaudePermissionConsent() {}

    static void configure(Boolean requested, Collection<Path> targets) {
        var console = System.console();
        configure(requested, targets, console == null ? null : console::readLine);
    }

    static void configure(Boolean requested, Collection<Path> targets,
            Function<String, String> prompt) {
        if (Boolean.FALSE.equals(requested)) return;
        var rules = ReadOnlyToolNames.all();
        var pending = targets.stream().distinct()
                .filter(target -> !ClaudeSettingsInstaller.areToolsAllowed(target, rules)).toList();
        if (pending.isEmpty()) return;
        if (requested == null && prompt == null) {
            System.out.println("[quill] Optional: rerun init with --allow-quill-tools to let Claude "
                    + "use Quill's read-only MCP tools without repeated approvals.");
            return;
        }
        String question = "Allow Claude to use Quill's current read-only MCP tools without "
                + "repeated approvals in " + pending.size() + " local project configuration(s)?\n"
                + "Quill analyzes code; it does not edit source, run builds/tests, or grant shell "
                + "access. Only .claude/settings.local.json is changed; no global settings.\n"
                + "Allow Quill tools? [y/N] ";
        if (requested == null) {
            String answer = prompt.apply(question);
            if (answer == null || !(answer.strip().equalsIgnoreCase("y")
                    || answer.strip().equalsIgnoreCase("yes"))) return;
        }
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
