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
        assertTrue(installed.contains("use ToolSearch to load the relevant Quill tools"));
        assertEquals(1, occurrences(installed, "<!-- quill:managed:start -->"));

        Files.writeString(claude, installed.replace(
                "use ToolSearch to load the relevant Quill tools", "obsolete instructions"));
        ProjectConfiguration.ensureClaudeMd(root);

        String refreshed = Files.readString(claude);
        assertTrue(refreshed.startsWith("# User instructions\n"));
        assertTrue(refreshed.contains("use ToolSearch to load the relevant Quill tools"));
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
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
