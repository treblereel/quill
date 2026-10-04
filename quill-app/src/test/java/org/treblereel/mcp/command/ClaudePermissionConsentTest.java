package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.ReadOnlyToolNames;

class ClaudePermissionConsentTest {
    @TempDir Path root;

    @Test
    void nonInteractiveOrDeclinedConsentNeverGrantsPermissions() {
        for (String answer : List.of("", "no", "n", "anything")) {
            ClaudePermissionConsent.configure(null, List.of(root), question -> answer);
            assertFalse(Files.exists(root.resolve(".claude")));
        }
        ClaudePermissionConsent.configure(null, List.of(root), null);
        ClaudePermissionConsent.configure(false, List.of(root), question -> fail("must not ask"));
        assertFalse(Files.exists(root.resolve(".claude")));
    }

    @Test
    void promptsOnceForAllTargetsAndSkipsAlreadyConfiguredTargets() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repository"));
        AtomicInteger questions = new AtomicInteger();
        ClaudePermissionConsent.configure(null, List.of(root, repository), question -> {
            assertTrue(question.contains("2 local project"));
            assertTrue(question.contains("no global settings"));
            questions.incrementAndGet();
            return "yes";
        });
        assertEquals(1, questions.get());
        for (Path target : List.of(root, repository)) {
            assertTrue(ClaudeSettingsInstaller.areToolsAllowed(target, ReadOnlyToolNames.all()));
        }
        ClaudePermissionConsent.configure(null, List.of(root, repository), question -> fail("must not ask again"));
    }

    @Test
    void explicitFlagDoesNotAskAndCatalogContainsOnlyConcreteNames() {
        var rules = ReadOnlyToolNames.all();
        assertTrue(rules.contains("mcp__quill__get_overview"));
        assertTrue(rules.contains("mcp__quill__search_tools"));
        assertTrue(rules.contains("mcp__quill__execute_tool"));
        assertTrue(rules.stream().allMatch(rule -> rule.matches("mcp__quill__[a-z][a-z0-9_]*")));
        ClaudePermissionConsent.configure(true, List.of(root), question -> fail("must not ask"));
        assertTrue(ClaudeSettingsInstaller.areToolsAllowed(root, rules));
    }
}
