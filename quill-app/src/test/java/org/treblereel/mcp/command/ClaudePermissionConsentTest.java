package org.treblereel.mcp.command;

import org.treblereel.mcp.index.ClaudeSettingsInstaller;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.server.ReadOnlyToolNames;

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

    // Every Quill tool only reads; the grant is the server rule, so tools added later need no new grant.
    @Test
    void initializationGrantsTheServerRuleAndReplacesTheConcreteNamesItGrantedBefore() throws Exception {
        Files.createDirectories(root.resolve(".claude"));
        Files.writeString(root.resolve(".claude/settings.local.json"),
                "{\"permissions\":{\"allow\":[\"Read\",\"mcp__quill__get_overview\",\"mcp__quill__find_usages\"]}}");
        Files.writeString(root.resolve(".claude/quill-permissions.json"),
                "{\"schema_version\":1,\"rules\":[\"mcp__quill__find_usages\"]}");
        ClaudePermissionConsent.configure(null, List.of(root));
        var settings = new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve(".claude/settings.local.json").toFile());
        assertEquals("[\"Read\",\"mcp__quill__get_overview\",\"mcp__quill\"]", settings.path("permissions").path("allow").toString());
        assertEquals(List.of("mcp__quill"), ClaudeSettingsInstaller.ownedRules(root));
        assertTrue(ClaudeSettingsInstaller.removeOwnedPermissions(root));
        assertEquals("[\"Read\",\"mcp__quill__get_overview\"]", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(root.resolve(".claude/settings.local.json").toFile()).path("permissions").path("allow").toString());
    }
}
