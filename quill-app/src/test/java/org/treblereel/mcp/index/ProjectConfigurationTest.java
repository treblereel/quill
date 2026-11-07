package org.treblereel.mcp.index;

import org.treblereel.mcp.index.ProjectConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConfigurationTest {

    @TempDir Path root;

    @Test
    void refreshesManagedClaudeInstructionsWithoutDuplicatingThem() throws Exception {
        Path claude = root.resolve("CLAUDE.md");
        Files.writeString(claude, "# User instructions\n");

        ProjectConfiguration.ensureClaudeMd(root);
        String installed = Files.readString(claude);
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT,
                ProjectConfiguration.inspectClaudeMd(root));
        assertTrue(installed.contains("Query the relevant tool directly"));
        assertTrue(installed.contains("exact name only for an unlisted tool"));
        assertTrue(installed.contains("`next_page_request`"));
        assertTrue(installed.contains("call `change_session(change, targets)` before editing"));
        assertTrue(installed.contains("`directive.primary_action`"));
        assertTrue(installed.contains("`phase_gate`"));
        assertTrue(installed.contains("`review_checklist`"));
        assertTrue(installed.contains("receipt is verified"));
        assertTrue(installed.contains("`quick_compile`"));
        assertTrue(installed.contains("never runs builds"));
        assertFalse(installed.contains("`structuredContent`"), "Server instructions already say this");
        assertTrue(installed.length() < 1150, "Instructions load into every session: " + installed.length());
        assertEquals(1, occurrences(installed, "<!-- quill:managed:start -->"));

        Files.writeString(claude, installed.replace(
                "Query the relevant tool directly", "obsolete instructions"));
        assertEquals(ProjectConfiguration.InstructionsState.OUTDATED,
                ProjectConfiguration.inspectClaudeMd(root));
        ProjectConfiguration.ensureClaudeMd(root);

        String refreshed = Files.readString(claude);
        assertTrue(refreshed.startsWith("# User instructions\n"));
        assertTrue(refreshed.contains("Query the relevant tool directly"));
        assertFalse(refreshed.contains("obsolete instructions"));
        assertEquals(1, occurrences(refreshed, "<!-- quill:managed:start -->"));
    }

    @Test
    void removalPreservesUserInstructionsAndDeletesQuillOnlyFile() throws Exception {
        Path claude = root.resolve("CLAUDE.md");
        Files.writeString(claude, "# User instructions\n");
        ProjectConfiguration.ensureClaudeMd(root);

        assertTrue(ProjectConfiguration.removeClaudeMd(root));
        assertEquals("# User instructions\n", Files.readString(claude));

        Files.delete(claude);
        ProjectConfiguration.ensureClaudeMd(root);
        assertTrue(ProjectConfiguration.removeClaudeMd(root));
        assertFalse(Files.exists(claude));
        assertEquals(ProjectConfiguration.InstructionsState.MISSING,
                ProjectConfiguration.inspectClaudeMd(root));
    }

    @Test
    void managesCodexInstructionsWithoutOverwritingUserContent() throws Exception {
        Path agents = root.resolve("AGENTS.md");
        Files.writeString(agents, "# User instructions\n");

        ProjectConfiguration.ensureAgentsMd(root);

        String installed = Files.readString(agents);
        assertTrue(installed.contains("`mcp__quill__change_session(change, targets)` before editing"));
        assertTrue(installed.contains("Empty resources do not mean missing tools"));
        assertTrue(installed.startsWith("# User instructions\n"));
        assertTrue(installed.contains("`mcp__quill__get_overview`"));
        assertTrue(installed.contains("never print the whole catalog"));
        assertTrue(installed.contains("`--workspace`"));
        assertTrue(installed.contains("`retry_with`"));
        assertTrue(installed.contains("`next_page_request`"));
        assertTrue(installed.contains("`structuredContent`"));
        assertTrue(installed.contains("`tools.mcp__quill__*`"));
        assertTrue(installed.contains("`directive.primary_action`"));
        assertTrue(installed.contains("`phase_gate`"));
        assertTrue(installed.contains("`review_checklist`"));
        assertTrue(installed.contains("phase is `complete`"));
        assertTrue(installed.contains("`quick_compile`"));
        assertTrue(installed.contains("never runs builds"));
        assertTrue(installed.length() < 1500, "Instructions load into every session: " + installed.length());
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT,
                ProjectConfiguration.inspectAgentsMd(root));
        assertTrue(ProjectConfiguration.removeAgentsMd(root));
        assertEquals("# User instructions\n", Files.readString(agents));
    }

    @Test
    void detectsAndRefreshesOutdatedManagedInstructions() throws Exception {
        Path agents = root.resolve("AGENTS.md");
        Files.writeString(agents, """
                # User instructions

                <!-- quill:managed:start -->
                ## Quill MCP
                Old guidance.
                <!-- quill:managed:end -->
                """);

        assertEquals(ProjectConfiguration.InstructionsState.OUTDATED,
                ProjectConfiguration.inspectAgentsMd(root));

        ProjectConfiguration.ensureAgentsMd(root);

        String refreshed = Files.readString(agents);
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT,
                ProjectConfiguration.inspectAgentsMd(root));
        assertTrue(refreshed.contains("# User instructions"));
        assertTrue(refreshed.contains("<!-- quill:instructions:v6 -->"));
        assertFalse(refreshed.contains("Old guidance."));
    }

    @Test
    void previousClaudeBlockIsReplacedWithoutTouchingUserText() throws Exception {
        Path claude = root.resolve("CLAUDE.md");
        Files.writeString(claude, """
                # User head

                <!-- quill:managed:start -->
                <!-- quill:instructions:v6 -->
                For a code change, call `change_session` before editing.
                <!-- quill:managed:end -->

                # User tail
                """);
        assertEquals(ProjectConfiguration.InstructionsState.OUTDATED,
                ProjectConfiguration.inspectClaudeMd(root));

        ProjectConfiguration.ensureClaudeMd(root);

        String refreshed = Files.readString(claude);
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT,
                ProjectConfiguration.inspectClaudeMd(root));
        assertTrue(refreshed.startsWith("# User head\n"));
        assertTrue(refreshed.endsWith("# User tail\n"));
        assertTrue(refreshed.contains("<!-- quill:instructions:v7 -->"));
        assertFalse(refreshed.contains("<!-- quill:instructions:v6 -->"));
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    @Test
    void malformedBoundariesNeverOverwriteOrDeleteUserText() throws Exception {
        for (String content : java.util.List.of(
                "<!-- quill:managed:start -->\nUser tail\n",
                "<!-- quill:managed:end -->\n<!-- quill:managed:start -->\nUser tail\n",
                "<!-- quill:managed:start --><!-- quill:managed:start -->"
                        + "User tail<!-- quill:managed:end -->")) {
            Path file = root.resolve("AGENTS.md");
            Files.writeString(file, content);
            assertEquals(ProjectConfiguration.InstructionsState.INVALID,
                    ProjectConfiguration.inspectAgentsMd(root));
            ProjectConfiguration.ensureAgentsMd(root);
            assertEquals(content, Files.readString(file));
            assertFalse(ProjectConfiguration.removeAgentsMd(root));
            assertEquals(content, Files.readString(file));
        }
    }

    @Test
    void versionMarkerCannotAttestModifiedContentOrAnExternalBlock() throws Exception {
        ProjectConfiguration.ensureClaudeMd(root);
        Path file = root.resolve("CLAUDE.md");
        String content = Files.readString(file);
        Files.writeString(file, content.replace("directive.primary_action", "obsolete"));
        assertEquals(ProjectConfiguration.InstructionsState.OUTDATED,
                ProjectConfiguration.inspectClaudeMd(root));
        Files.writeString(file, "<!-- quill:instructions:v2 -->\n"
                + "<!-- quill:managed:start -->\nOld instructions\n<!-- quill:managed:end -->\n");
        assertEquals(ProjectConfiguration.InstructionsState.OUTDATED,
                ProjectConfiguration.inspectClaudeMd(root));
    }
}
