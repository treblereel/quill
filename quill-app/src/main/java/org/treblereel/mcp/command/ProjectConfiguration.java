package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.treblereel.mcp.QuillLauncher;

/** Owns the reversible project configuration installed around an index generation. */
final class ProjectConfiguration {

    private static final String QUILL_SECTION_MARKER = "## Quill — Codebase Intelligence (MCP)";
    private static final String QUILL_CLAUDE_MD = """

            ## Quill — Codebase Intelligence (MCP)

            This project is indexed by Quill. **Always prefer Quill MCP tools over grep/find/Explore agents** for:

            - **Searching classes:** `search_classes` — faster than grep, supports wildcard patterns (`*Service`, `*Strategy*`)
            - **Dependency analysis:** `get_dependencies` — what a class depends on and what depends on it
            - **Risk assessment:** `assess_change_risk` — class blast radius or file risk from criticality, churn, bus factor, and coupling
            - **Project overview:** `get_overview` — call first to orient (class/bean counts, architecture hubs, problems)
            - **Git hotspots:** `find_git_hotspots` — most frequently changed files/classes
            - **Co-change analysis:** `find_co_changed_files` — files that change together (hidden coupling)
            - **Beans:** `list_beans` — list/filter beans by scope, kind, qualifier (CDI and Spring)
            - **Injection points:** `list_injection_points` — injection resolution status for a bean
            - **External deps:** `list_external_dependencies` — third-party library usage

            **Tip:** Add `"alwaysLoad": true` to the quill server in `.mcp.json` so tool schemas are loaded eagerly (no ToolSearch needed).
            """;

    private ProjectConfiguration() {}

    static void prepareForIndex(Path root, boolean indexOnly) {
        if (indexOnly) return;
        ensureGitignore(root);
        BuildIntegrationInstaller.install(root);
    }

    static void finishInitialization(Path root, boolean indexOnly) {
        if (!indexOnly) ensureClaudeMd(root);
        ensureCodexConfig(root, indexOnly);
    }

    static CodexConfigInstaller.Result ensureCodexConfig(Path root, boolean indexOnly) {
        if (indexOnly) return CodexConfigInstaller.Result.SKIPPED;
        return CodexConfigInstaller.installIfPresent(root, QuillLauncher.detect());
    }

    private static void ensureClaudeMd(Path root) {
        Path claudeMd = root.resolve("CLAUDE.md");
        try {
            if (Files.exists(claudeMd)) {
                String content = Files.readString(claudeMd);
                if (content.contains(QUILL_SECTION_MARKER)) return;
                String separator = content.endsWith("\n") ? "" : "\n";
                Files.writeString(claudeMd, content + separator + QUILL_CLAUDE_MD);
            } else {
                Files.writeString(claudeMd, QUILL_CLAUDE_MD.stripLeading());
            }
            System.err.println("[quill] Updated CLAUDE.md with Quill tool instructions.");
        } catch (IOException error) {
            System.err.println("[quill] Warning: could not update CLAUDE.md: " + error.getMessage());
        }
    }

    private static void ensureGitignore(Path root) {
        Path gitignore = root.resolve(".gitignore");
        String entry = ".quill/";
        try {
            if (Files.exists(gitignore)) {
                String content = Files.readString(gitignore);
                if (content.contains(entry)) return;
                String separator = content.endsWith("\n") ? "" : "\n";
                Files.writeString(gitignore, content + separator + entry + "\n");
            } else {
                Files.writeString(gitignore, entry + "\n");
            }
        } catch (IOException ignored) {
            // Best effort; failure to update ignore rules must not invalidate the index.
        }
    }
}
