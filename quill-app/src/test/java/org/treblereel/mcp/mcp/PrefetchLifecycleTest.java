package org.treblereel.mcp.mcp;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
class PrefetchLifecycleTest {
    @TempDir Path root;
    String hook(String event,String session) throws Exception {
        var json=new ObjectMapper().createObjectNode().put("hook_event_name",event).put("session_id",session).put("prompt","Explain Fixture").put("cwd",root.toString());
        var input=System.in;var output=System.out;var bytes=new ByteArrayOutputStream();
        try {System.setIn(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
            assertEquals(0,new picocli.CommandLine(new org.treblereel.mcp.command.PrefetchCommand()).execute("--project",root.toString()));
        }finally{System.setIn(input);System.setOut(output);}
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test void sameSessionSuppressesFactsResetAndNewSessionDeliverAgainWithoutMcpInitialization() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int value;}");
        org.treblereel.mcp.db.QuillDatabase.retire(db);
        Files.createDirectories(root.resolve(".quill"));Files.copy(root.resolve("index.db"),root.resolve(".quill/fixture.db"));
        Files.writeString(root.resolve(".quill/refs.json"),"{\"@worktree\":\"fixture\"}");
        assertNotNull(org.treblereel.mcp.command.ProjectIndexStore.findBestAvailableDb(root));
        assertTrue(hook("UserPromptSubmit","one").contains("additionalContext"));
        assertEquals("",hook("UserPromptSubmit","one"));
        assertTrue(hook("UserPromptSubmit","two").contains("additionalContext"));
        assertEquals("",hook("SessionStart","one"));
        assertTrue(hook("UserPromptSubmit","one").contains("additionalContext"));
    }
}
