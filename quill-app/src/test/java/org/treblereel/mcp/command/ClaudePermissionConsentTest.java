package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.ReadOnlyToolNames;

class ClaudePermissionConsentTest {
    @TempDir Path root;

    @Test
    void explicitOptOutNeverGrantsPermissions() {
        ClaudePermissionConsent.configure(false, List.of(root));
        assertFalse(Files.exists(root.resolve(".claude")));
    }

    @Test
    void defaultInitializationGrantsForAllTargetsWithoutInteractionAndIsIdempotent() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repository"));
        ClaudePermissionConsent.configure(null, List.of(root, repository));
        for (Path target : List.of(root, repository)) {
            assertTrue(ClaudeSettingsInstaller.areToolsAllowed(target, ReadOnlyToolNames.all()));
        }
        String before=Files.readString(root.resolve(".claude/settings.local.json"));
        ClaudePermissionConsent.configure(null, List.of(root, repository));
        assertEquals(before,Files.readString(root.resolve(".claude/settings.local.json")));
    }

    @Test
    void explicitFlagDoesNotAskAndCatalogContainsOnlyConcreteNames() {
        var rules = ReadOnlyToolNames.all();
        assertTrue(rules.contains("mcp__quill__get_overview"));
        assertTrue(rules.contains("mcp__quill__search_tools"));
        assertTrue(rules.contains("mcp__quill__execute_tool"));
        assertTrue(rules.stream().allMatch(rule -> rule.matches("mcp__quill__[a-z][a-z0-9_]*")));
        ClaudePermissionConsent.configure(true, List.of(root));
        assertTrue(ClaudeSettingsInstaller.areToolsAllowed(root, rules));
    }
}
