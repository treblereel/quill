package org.treblereel.mcp.command;

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
        assertTrue(installed.contains("call `get_overview`"));
        assertEquals(1, occurrences(installed, "<!-- quill:managed:start -->"));

        Files.writeString(claude, installed.replace(
                "call `get_overview`", "obsolete instructions"));
        ProjectConfiguration.ensureClaudeMd(root);

        String refreshed = Files.readString(claude);
        assertTrue(refreshed.startsWith("# User instructions\n"));
        assertTrue(refreshed.contains("call `get_overview`"));
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
        assertTrue(installed.startsWith("# User instructions\n"));
        assertTrue(installed.contains("`mcp__quill__get_overview`"));
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT,
                ProjectConfiguration.inspectAgentsMd(root));
        assertTrue(ProjectConfiguration.removeAgentsMd(root));
        assertEquals("# User instructions\n", Files.readString(agents));
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
