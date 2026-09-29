package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.treblereel.mcp.QuillLauncher;

/** Owns the reversible project configuration installed around an index generation. */
final class ProjectConfiguration {

    enum ClaudeInstructionsState { CURRENT, MISSING, INVALID }

    private static final String CLAUDE_BLOCK_START = "<!-- quill:managed:start -->";
    private static final String CLAUDE_BLOCK_END = "<!-- quill:managed:end -->";
    private static final String QUILL_CLAUDE_MD = """
            <!-- quill:managed:start -->
            ## Quill — Codebase Intelligence (MCP)

            Before broad code search, dependency or impact analysis, or running a large test suite,
            use ToolSearch to load the relevant Quill tools. Prefer Quill for project-wide semantic
            questions: implementations, annotations, endpoints, DI, dependency graphs, affected tests,
            build problems, generated code, Git history, and change risk.

            Use `rg` and direct source reads for an exact literal, a known file, or one concrete
            occurrence. Do not query both by default. Verify Quill results in source when the index
            reports stale, partial, unknown, or unsupported evidence.
            <!-- quill:managed:end -->
            """;

    private ProjectConfiguration() {}

    static void prepareForIndex(Path root, boolean indexOnly) {
        if (indexOnly) return;
        ensureGitignore(root);
        BuildIntegrationInstaller.install(root);
    }

    static void finishInitialization(Path root, boolean indexOnly) {
        if (!indexOnly) {
            ensureClaudeMd(root);
            ensureMcpJson(root);
        }
        ensureCodexConfig(root, indexOnly);
    }

    static McpJsonInstaller.Result ensureMcpJson(Path root) {
        try {
            McpJsonInstaller.Result result =
                    McpJsonInstaller.installProject(root, QuillLauncher.detect());
            if (result == McpJsonInstaller.Result.ADDED
                    || result == McpJsonInstaller.Result.REPLACED) {
                System.err.println("[quill] Updated " + root.resolve(".mcp.json")
                        + " with the Quill MCP server.");
            } else if (result == McpJsonInstaller.Result.UNSUPPORTED) {
                System.err.println("[quill] Warning: could not update "
                        + root.resolve(".mcp.json") + ": unsupported structure");
            }
            return result;
        } catch (IOException error) {
            System.err.println("[quill] Warning: could not update "
                    + root.resolve(".mcp.json") + ": " + error.getMessage());
            return McpJsonInstaller.Result.UNSUPPORTED;
        }
    }

    static CodexConfigInstaller.Result ensureCodexConfig(Path root, boolean indexOnly) {
        if (indexOnly) return CodexConfigInstaller.Result.SKIPPED;
        return CodexConfigInstaller.install(root, QuillLauncher.detect());
    }

    static void ensureClaudeMd(Path root) {
        Path claudeMd = root.resolve("CLAUDE.md");
        try {
            String content = Files.exists(claudeMd) ? Files.readString(claudeMd) : "";
            int start = content.indexOf(CLAUDE_BLOCK_START);
            int end = start < 0 ? -1 : content.indexOf(CLAUDE_BLOCK_END, start);
            String updated;
            if (start >= 0) {
                int after = end < 0 ? content.length() : end + CLAUDE_BLOCK_END.length();
                updated = content.substring(0, start) + QUILL_CLAUDE_MD.strip()
                        + content.substring(after);
            } else {
                String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
                String gap = content.isEmpty() ? "" : "\n";
                updated = content + separator + gap + QUILL_CLAUDE_MD.strip() + "\n";
            }
            Files.writeString(claudeMd, updated);
            System.err.println("[quill] Updated CLAUDE.md with Quill tool instructions.");
        } catch (IOException error) {
            System.err.println("[quill] Warning: could not update CLAUDE.md: " + error.getMessage());
        }
    }

    static boolean removeClaudeMd(Path root) throws IOException {
        Path claudeMd = root.resolve("CLAUDE.md");
        if (!Files.isRegularFile(claudeMd)) return false;
        String content = Files.readString(claudeMd);
        int start = content.indexOf(CLAUDE_BLOCK_START);
        if (start < 0) return false;
        int end = content.indexOf(CLAUDE_BLOCK_END, start);
        int after = end < 0 ? content.length() : end + CLAUDE_BLOCK_END.length();
        String updated = (content.substring(0, start) + content.substring(after))
                .replaceFirst("\\s+$", "");
        if (updated.isBlank()) Files.delete(claudeMd);
        else Files.writeString(claudeMd, updated + "\n");
        return true;
    }

    static ClaudeInstructionsState inspectClaudeMd(Path root) {
        Path claudeMd = root.resolve("CLAUDE.md");
        if (!Files.isRegularFile(claudeMd)) return ClaudeInstructionsState.MISSING;
        try {
            String content = Files.readString(claudeMd);
            boolean start = content.contains(CLAUDE_BLOCK_START);
            boolean end = content.contains(CLAUDE_BLOCK_END);
            if (!start && !end) return ClaudeInstructionsState.MISSING;
            return start && end ? ClaudeInstructionsState.CURRENT
                    : ClaudeInstructionsState.INVALID;
        } catch (IOException error) {
            return ClaudeInstructionsState.INVALID;
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
