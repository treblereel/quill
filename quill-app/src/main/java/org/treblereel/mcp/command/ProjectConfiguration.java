package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.treblereel.mcp.QuillLauncher;

/** Owns the reversible project configuration installed around an index generation. */
final class ProjectConfiguration {

    enum InstructionsState { CURRENT, MISSING, INVALID }

    private static final String CLAUDE_BLOCK_START = "<!-- quill:managed:start -->";
    private static final String CLAUDE_BLOCK_END = "<!-- quill:managed:end -->";
    private static final String QUILL_CLAUDE_MD = """
            <!-- quill:managed:start -->
            ## Quill — Codebase Intelligence (MCP)

            At the beginning of a coding or code-analysis task, call `get_overview`. Before broad
            code search, dependency or impact analysis, or running a large test suite, prefer Quill
            for project-wide semantic
            questions: implementations, annotations, endpoints, DI, dependency graphs, affected tests,
            build problems, generated code, Git history, and change risk.

            Use `rg` and direct source reads for an exact literal, a known file, or one concrete
            occurrence. Do not query both by default. Verify Quill results in source when the index
            reports stale, partial, unknown, or unsupported evidence.

            For a code change, prefer `change_session` for one stateless snapshot of the plan,
            verification evidence, and ordered next actions; repeat it with the same targets and
            change description to refresh the snapshot, or omit targets after editing to infer them
            from dirty JVM sources. Use `plan_change` or `verify_change` when only that focused view
            is needed. Keep the default `view=auto` and summary detail so only the current phase is
            returned; request another view or full detail only to inspect omitted evidence. When
            build evidence is missing or stale, prefer the
            `verification_plan` command whose scope is `quick_compile`: run its exact `argv` from
            `working_directory` in the external shell, then verify again. This compiles production
            and standard test sources without running tests. Treat `focused` and `module_fallback` as
            separate test recommendations; run them only when the task or user requires tests. Quill
            recommends commands but never executes a build or persists change-session state.
            <!-- quill:managed:end -->
            """;
    private static final String QUILL_AGENTS_MD = """
            <!-- quill:managed:start -->
            ## Quill MCP

            Quill is the primary code-intelligence tool for this repository. At the beginning of a
            coding or code-analysis task, discover the `mcp__quill__*` tools and call
            `mcp__quill__get_overview`.

            Use Quill before broad filesystem searches for symbols, implementations, usages, call
            and type hierarchies, dependencies, architecture, framework endpoints, dependency
            injection, affected tests, build diagnostics, Git history, and change risk. Quill tools
            may be deferred and absent from the initially displayed tool list; search the complete
            tool catalog before concluding that Quill is unavailable.

            Use `rg` and direct source reads for exact literals, known files, and verification when
            Quill reports stale, partial, unknown, or unsupported evidence.

            For a code change, prefer `mcp__quill__change_session` for one stateless snapshot of the
            plan, verification evidence, and ordered next actions; repeat it with the same targets
            and change description to refresh the snapshot, or omit targets after editing to infer
            them from dirty JVM sources. Use `mcp__quill__plan_change` or `mcp__quill__verify_change`
            for a focused view. Keep the default `view=auto` and summary detail so only the current
            phase is returned; request another view or full detail only to inspect omitted evidence.
            If build evidence is missing or stale,
            prefer the `verification_plan` command with scope `quick_compile`: run its exact `argv`
            from `working_directory` using the terminal, then verify again. It compiles production
            and standard test sources without running tests. Commands with scope `focused` or
            `module_fallback` are separate test recommendations; run them only when the task or user
            requires tests. Quill recommends commands but does not execute builds or persist
            change-session state.
            <!-- quill:managed:end -->
            """;

    private ProjectConfiguration() {}

    static BuildIntegrationInstaller.Result prepareForIndex(Path root, boolean indexOnly) {
        if (indexOnly) return BuildIntegrationInstaller.Result.NOT_FOUND;
        ensureGitignore(root);
        return BuildIntegrationInstaller.install(root);
    }

    static void finishInitialization(Path root, boolean indexOnly) {
        if (!indexOnly) {
            ensureClaudeMd(root);
            ensureAgentsMd(root);
            ClaudeSettingsInstaller.install(root);
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
        ensureInstructions(root.resolve("CLAUDE.md"), QUILL_CLAUDE_MD,
                "CLAUDE.md with Quill tool instructions");
    }

    static void ensureAgentsMd(Path root) {
        ensureInstructions(root.resolve("AGENTS.md"), QUILL_AGENTS_MD,
                "AGENTS.md with Quill tool instructions");
    }

    private static void ensureInstructions(Path file, String managedBlock, String description) {
        try {
            String content = Files.exists(file) ? Files.readString(file) : "";
            int start = content.indexOf(CLAUDE_BLOCK_START);
            int end = start < 0 ? -1 : content.indexOf(CLAUDE_BLOCK_END, start);
            String updated;
            if (start >= 0) {
                int after = end < 0 ? content.length() : end + CLAUDE_BLOCK_END.length();
                updated = content.substring(0, start) + managedBlock.strip()
                        + content.substring(after);
            } else {
                String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
                String gap = content.isEmpty() ? "" : "\n";
                updated = content + separator + gap + managedBlock.strip() + "\n";
            }
            Files.writeString(file, updated);
            System.err.println("[quill] Updated " + description + ".");
        } catch (IOException error) {
            System.err.println("[quill] Warning: could not update " + file + ": "
                    + error.getMessage());
        }
    }

    static boolean removeClaudeMd(Path root) throws IOException {
        return removeInstructions(root.resolve("CLAUDE.md"));
    }

    static boolean removeAgentsMd(Path root) throws IOException {
        return removeInstructions(root.resolve("AGENTS.md"));
    }

    private static boolean removeInstructions(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        String content = Files.readString(file);
        int start = content.indexOf(CLAUDE_BLOCK_START);
        if (start < 0) return false;
        int end = content.indexOf(CLAUDE_BLOCK_END, start);
        int after = end < 0 ? content.length() : end + CLAUDE_BLOCK_END.length();
        String updated = (content.substring(0, start) + content.substring(after))
                .replaceFirst("\\s+$", "");
        if (updated.isBlank()) Files.delete(file);
        else Files.writeString(file, updated + "\n");
        return true;
    }

    static InstructionsState inspectClaudeMd(Path root) {
        return inspectInstructions(root.resolve("CLAUDE.md"));
    }

    static InstructionsState inspectAgentsMd(Path root) {
        return inspectInstructions(root.resolve("AGENTS.md"));
    }

    private static InstructionsState inspectInstructions(Path file) {
        if (!Files.isRegularFile(file)) return InstructionsState.MISSING;
        try {
            String content = Files.readString(file);
            boolean start = content.contains(CLAUDE_BLOCK_START);
            boolean end = content.contains(CLAUDE_BLOCK_END);
            if (!start && !end) return InstructionsState.MISSING;
            return start && end ? InstructionsState.CURRENT : InstructionsState.INVALID;
        } catch (IOException error) {
            return InstructionsState.INVALID;
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
