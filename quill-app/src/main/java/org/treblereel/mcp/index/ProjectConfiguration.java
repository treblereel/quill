package org.treblereel.mcp.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.treblereel.mcp.QuillLauncher;

/** Owns the reversible project configuration installed around an index generation. */
public final class ProjectConfiguration {

    public enum InstructionsState { CURRENT, MISSING, OUTDATED, INVALID }

    private static final String CLAUDE_BLOCK_START = "<!-- quill:managed:start -->";
    private static final String CLAUDE_BLOCK_END = "<!-- quill:managed:end -->";
    private static final String QUILL_CLAUDE_MD = """
            <!-- quill:managed:start -->
            <!-- quill:instructions:v7 -->
            ## Quill — Codebase Intelligence (MCP)

            Prefer Quill over broad search for project-wide questions: implementations, annotations,
            endpoints, DI, dependency graphs, affected tests, build problems, Git history, change
            risk. Use `rg` and source reads for exact literals and known files, and to verify
            evidence Quill reports as stale, partial, unknown, or unsupported.

            Query the relevant tool directly, sending independent queries as parallel calls in one
            message. Follow `next_page_request`. Search the tool catalog by
            exact name only for an unlisted tool.

            For a code change, call `change_session(change, targets)` before editing or choosing a
            build, repeat it after edits, and follow `directive.primary_action`. Required `phase_gate` evidence and
            `review_checklist` items are blockers. When build evidence is stale, run the
            `quick_compile` command; Quill never runs builds. Report completion only when the phase
            is `complete` and the verification receipt is verified.
            <!-- quill:managed:end -->
            """;
    private static final String QUILL_AGENTS_MD = """
            <!-- quill:managed:start -->
            <!-- quill:instructions:v6 -->
            ## Quill MCP

            Prefer Quill over broad search for project-wide questions: symbols, implementations,
            usages, hierarchies, dependencies, endpoints, DI, affected tests, build problems, Git
            history, change risk. Use `rg` and source reads for exact literals and known files, and
            to verify evidence Quill reports as stale, partial, unknown, or unsupported.

            Query the relevant tool directly. Each turn resends the conversation, so make all
            independent queries in one script turn (several `tools.mcp__quill__*` calls awaited
            together). Tools may be deferred: search the catalog by exact name only for an unlisted
            tool, and never print the whole catalog. Empty resources do not mean missing tools.
            `mcp__quill__get_overview` is optional; omit its arguments unless the server runs with
            `--workspace`. Read `structuredContent` even if text is empty. Follow `retry_with` and
            `next_page_request`.

            For a code change, call `mcp__quill__change_session(change, targets)` before editing or
            choosing a build, repeat it after edits, and follow `directive.primary_action`. Required
            `phase_gate` evidence and `review_checklist` items are blockers. When build evidence is
            stale, run the `quick_compile` command; Quill never runs builds. Report completion only
            when the phase is `complete` and the verification receipt is verified.
            <!-- quill:managed:end -->
            """;

    private ProjectConfiguration() {}

    public static BuildIntegrationInstaller.Result prepareForIndex(Path root, boolean indexOnly) {
        if (indexOnly) return BuildIntegrationInstaller.Result.NOT_FOUND;
        ensureGitignore(root);
        return BuildIntegrationInstaller.install(root);
    }

    public static void finishInitialization(Path root, boolean indexOnly) {
        if (!indexOnly) {
            ensureClaudeMd(root);
            ensureAgentsMd(root);
            ClaudeSettingsInstaller.install(root);
            ensureMcpJson(root);
            PrefetchHooksInstaller.install(root, QuillLauncher.detect());
        }
        ensureCodexConfig(root, indexOnly);
    }

    public static McpJsonInstaller.Result ensureMcpJson(Path root) {
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

    public static CodexConfigInstaller.Result ensureCodexConfig(Path root, boolean indexOnly) {
        if (indexOnly) return CodexConfigInstaller.Result.SKIPPED;
        return CodexConfigInstaller.install(root, QuillLauncher.detect());
    }

    public static void ensureClaudeMd(Path root) {
        ensureInstructions(root.resolve("CLAUDE.md"), QUILL_CLAUDE_MD,
                "CLAUDE.md with Quill tool instructions");
    }

    public static void ensureAgentsMd(Path root) {
        ensureInstructions(root.resolve("AGENTS.md"), QUILL_AGENTS_MD,
                "AGENTS.md with Quill tool instructions");
    }

    private static void ensureInstructions(Path file, String managedBlock, String description) {
        try {
            if (Files.isSymbolicLink(file) || (Files.exists(file) && !Files.isRegularFile(file))) {
                System.err.println("[quill] Warning: instructions are not a regular file: " + file);
                return;
            }
            String content = Files.exists(file) ? Files.readString(file) : "";
            int start = content.indexOf(CLAUDE_BLOCK_START);
            int end = start < 0 ? -1 : content.indexOf(CLAUDE_BLOCK_END, start);
            if (!validMarkers(content)) {
                System.err.println("[quill] Warning: malformed Quill markers in " + file
                        + "; repair the marker boundaries before refreshing instructions.");
                return;
            }
            String updated;
            if (start >= 0) {
                int after = end + CLAUDE_BLOCK_END.length();
                updated = content.substring(0, start) + managedBlock.strip()
                        + content.substring(after);
            } else {
                String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
                String gap = content.isEmpty() ? "" : "\n";
                updated = content + separator + gap + managedBlock.strip() + "\n";
            }
            if (content.equals(updated)) return;
            Files.writeString(file, updated);
            System.err.println("[quill] Updated " + description + ".");
        } catch (IOException error) {
            System.err.println("[quill] Warning: could not update " + file + ": "
                    + error.getMessage());
        }
    }

    public static boolean removeClaudeMd(Path root) throws IOException {
        return removeInstructions(root.resolve("CLAUDE.md"));
    }

    public static boolean removeAgentsMd(Path root) throws IOException {
        return removeInstructions(root.resolve("AGENTS.md"));
    }

    private static boolean removeInstructions(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        String content = Files.readString(file);
        if (!validMarkers(content)) {
            System.err.println("[quill] Warning: malformed Quill markers in " + file
                    + "; instructions were preserved.");
            return false;
        }
        int start = content.indexOf(CLAUDE_BLOCK_START);
        if (start < 0) return false;
        int end = content.indexOf(CLAUDE_BLOCK_END, start);
        int after = end + CLAUDE_BLOCK_END.length();
        String updated = (content.substring(0, start) + content.substring(after))
                .replaceFirst("\\s+$", "");
        if (updated.isBlank()) Files.delete(file);
        else Files.writeString(file, updated + "\n");
        return true;
    }

    public static InstructionsState inspectClaudeMd(Path root) {
        return inspectInstructions(root.resolve("CLAUDE.md"), QUILL_CLAUDE_MD);
    }

    public static InstructionsState inspectAgentsMd(Path root) {
        return inspectInstructions(root.resolve("AGENTS.md"), QUILL_AGENTS_MD);
    }

    private static InstructionsState inspectInstructions(Path file, String expected) {
        if (!Files.isRegularFile(file)) return InstructionsState.MISSING;
        try {
            String content = Files.readString(file);
            boolean start = content.contains(CLAUDE_BLOCK_START);
            boolean end = content.contains(CLAUDE_BLOCK_END);
            if (!start && !end) return InstructionsState.MISSING;
            if (!validMarkers(content)) return InstructionsState.INVALID;
            String block = content.substring(content.indexOf(CLAUDE_BLOCK_START),
                    content.indexOf(CLAUDE_BLOCK_END) + CLAUDE_BLOCK_END.length());
            return block.replace("\r\n", "\n").equals(expected.strip())
                    ? InstructionsState.CURRENT : InstructionsState.OUTDATED;
        } catch (IOException error) {
            return InstructionsState.INVALID;
        }
    }

    public static boolean validMarkers(String content) {
        int start = content.indexOf(CLAUDE_BLOCK_START);
        int end = content.indexOf(CLAUDE_BLOCK_END);
        if (start < 0 && end < 0) return true;
        return start >= 0 && end > start
                && content.indexOf(CLAUDE_BLOCK_START, start + CLAUDE_BLOCK_START.length()) < 0
                && content.indexOf(CLAUDE_BLOCK_END, end + CLAUDE_BLOCK_END.length()) < 0;
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
