package org.treblereel.mcp.index;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class PrefetchHooksInstallerTest {
    @TempDir Path root;
    @Test void installationIsIdempotentPreservesOtherHooksAndUninstallsOnlyOwnedCommands() throws Exception {
        Path file=root.resolve(".claude/settings.json");Files.createDirectories(file.getParent());
        Files.writeString(file,"{\"permissions\":{\"ask\":[\"Bash\"]},\"hooks\":{\"UserPromptSubmit\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"keep me\"}]}]}}");
        String binary=root.resolve("bin/Quill's tool").toString();
        assertTrue(PrefetchHooksInstaller.install(root,binary));assertTrue(PrefetchHooksInstaller.install(root,binary));
        var doc=new ObjectMapper().readTree(file.toFile());assertEquals(2,doc.path("hooks").path("UserPromptSubmit").size());
        assertEquals("startup|clear|compact|fork",doc.path("hooks").path("SessionStart").get(0).path("matcher").asText());
        assertEquals(10,doc.path("hooks").path("SessionStart").get(0).path("hooks").get(0).path("timeout").asInt());
        assertEquals(30,doc.path("hooks").path("UserPromptSubmit").get(1).path("hooks").get(0).path("timeout").asInt());
        var codex=new ObjectMapper().readTree(root.resolve(".codex/hooks.json").toFile());
        assertEquals(30,codex.path("hooks").path("UserPromptSubmit").get(0).path("hooks").get(0).path("timeout").asInt());
        assertTrue(doc.toString().contains("keep me"));assertTrue(doc.toString().contains("ask"));
        PrefetchHooksInstaller.uninstall(root);doc=new ObjectMapper().readTree(file.toFile());
        assertEquals(1,doc.path("hooks").path("UserPromptSubmit").size());assertEquals(0,doc.path("hooks").path("SessionStart").size());
    }
    @Test void malformedConfigurationIsNotOverwritten() throws Exception {
        Path file=root.resolve(".codex/hooks.json");Files.createDirectories(file.getParent());Files.writeString(file,"{broken");
        assertFalse(PrefetchHooksInstaller.install(root,root.resolve("quill").toString()));assertEquals("{broken",Files.readString(file));
        assertFalse(Files.exists(root.resolve(".claude/settings.json")));
    }
}
