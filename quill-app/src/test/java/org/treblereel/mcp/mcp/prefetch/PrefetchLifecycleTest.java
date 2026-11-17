package org.treblereel.mcp.mcp.prefetch;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
class PrefetchLifecycleTest {
    @TempDir Path root;
    String hook(String event,String session) throws Exception {return hook(event,session,"Explain Fixture");}
    String hook(String event,String session,String prompt) throws Exception {
        var json=new ObjectMapper().createObjectNode().put("hook_event_name",event).put("session_id",session).put("prompt",prompt).put("cwd",root.toString());
        var input=System.in;var output=System.out;var bytes=new ByteArrayOutputStream();
        try {System.setIn(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)));System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
            assertEquals(0,new picocli.CommandLine(new org.treblereel.mcp.command.PrefetchCommand()).execute("--project",root.toString()));
        }finally{System.setIn(input);System.setOut(output);}
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test void sameSessionSuppressesFactsResetAndNewSessionDeliverAgainWithoutMcpInitialization() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int value;}");
        
        Files.createDirectories(root.resolve(".quill"));Files.copy(root.resolve("index.db"),root.resolve(".quill/fixture.db"));
        Files.writeString(root.resolve(".quill/refs.json"),"{\"@worktree\":\"fixture\"}");
        assertNotNull(org.treblereel.mcp.index.ProjectIndexStore.findBestAvailableDb(root));
        var mapper=new ObjectMapper();
        var context=mapper.readTree(mapper.readTree(hook("UserPromptSubmit","one")).path("hookSpecificOutput").path("additionalContext").asText());
        assertEquals("quill.prefetch",context.path("producer").asText());
        assertEquals("ready",context.path("status").asText());
        assertTrue(context.path("instructions").asText().contains("were cut to fit"));
        assertFalse(context.path("instructions").asText().contains("do not prove deployed values"), "No configuration fact");
        assertFalse(context.path("instructions").asText().contains("dependency jars do not add to"), "No framework contract");
        assertEquals("",hook("UserPromptSubmit","one"));
        assertTrue(hook("UserPromptSubmit","two").contains("additionalContext"));
        assertEquals("",hook("SessionStart","one"));
        assertTrue(hook("UserPromptSubmit","one").contains("additionalContext"));
    }
    @Test void oversizedFactIsNeverMarkedDeliveredOrSilentlySuppressed() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {}");
        db.useHandle(h->h.execute("UPDATE classes SET class_name=? WHERE class_name='p.Fixture'","p."+"x".repeat(12000)+".Fixture"));
        
        Files.createDirectories(root.resolve(".quill"));Files.copy(root.resolve("index.db"),root.resolve(".quill/fixture.db"));
        Files.writeString(root.resolve(".quill/refs.json"),"{\"@worktree\":\"fixture\"}");
        var mapper=new ObjectMapper();
        for(int i=0;i<2;i++){
            var data=mapper.readTree(mapper.readTree(hook("UserPromptSubmit","oversized")).path("hookSpecificOutput").path("additionalContext").asText());
            assertEquals("partial",data.path("status").asText(),data.toString());assertTrue(data.path("facts").isEmpty());
            assertEquals("prefetch_context_limit",data.path("unavailable").get(0).path("status").asText());
            assertTrue(data.path("instructions").asText().contains("Quill already ran these reads"),data.toString());
            assertTrue(data.path("instructions").asText().length()<300,"Every packet carries this text");
            assertTrue(data.toString().getBytes(StandardCharsets.UTF_8).length<=org.treblereel.mcp.mcp.prefetch.PrefetchContext.CONTEXT_LIMIT_BYTES);
            try(var files=Files.list(root.resolve(".quill/prefetch"))){var state=mapper.readTree(files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow().toFile());assertTrue(state.isEmpty(),state.toString());}
        }
    }
    /**
     * Apollo AQ-050 (Claude): the operation packet lost only its descriptor to the budget, and the
     * reset that followed dropped the anchor; the next untargeted step got the project overview
     * (4.3 KB, nothing about the operation) and the later steps nothing.
     */
    @Test void aTruncatedOperationPacketKeepsItsAnchorAndGetsNoProjectOverviewNext() throws Exception {
        // A handler body near the source limit plus its calls to long-named methods overflow the budget.
        StringBuilder methods=new StringBuilder(),body=new StringBuilder();
        String longName="aVeryLongMethodNameThatTakesRoomInThePacketAndInTheBody".repeat(2);
        for(int i=0;i<30;i++){methods.append("static void ").append(longName).append(i).append("(){} ");body.append("K.").append(longName).append(i).append("(); ");}
        var db=JavaEvidenceFixture.index(root,"package p; class K {"+methods+"} class Fixture {int fetch(){"+body+"return 1;}}");
        EndpointPrefetchTest.routes(db,"GET");
        Files.createDirectories(root.resolve(".quill"));Files.copy(root.resolve("index.db"),root.resolve(".quill/fixture.db"));
        Files.writeString(root.resolve(".quill/refs.json"),"{\"@worktree\":\"fixture\"}");
        var first=hook("UserPromptSubmit","long","GET /warehouse/items/{key}");
        assertTrue(first.contains("context_truncated"),first);
        var next=hook("UserPromptSubmit","long","What configuration does it use?");
        assertFalse(next.contains("\\\"kind\\\":\\\"project\\\""),next);
        assertEquals("",next,"the operation packet was delivered once; the step repeats nothing");
        var mapper=new ObjectMapper();
        try(var files=Files.list(root.resolve(".quill/prefetch"))){var state=mapper.readTree(files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow().toFile());assertEquals("GET /warehouse/items/{key}",state.path("_endpoint").asText(),state.toString());}
        // Not acknowledged, the packet comes again when the prompt names the route again.
        assertTrue(hook("UserPromptSubmit","long","GET /warehouse/items/{key}").contains("endpoint_anchor"));
    }
    @Test void operationAnchorSurvivesFollowUpsButNotResetOrAnExplicitUnknownRoute() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int fetch(){return 1;}}");
        EndpointPrefetchTest.routes(db,"GET");
        Files.createDirectories(root.resolve(".quill"));Files.copy(root.resolve("index.db"),root.resolve(".quill/fixture.db"));
        Files.writeString(root.resolve(".quill/refs.json"),"{\"@worktree\":\"fixture\"}");
        assertTrue(hook("UserPromptSubmit","route","GET /warehouse/items/{key}").contains("endpoint_anchor"));
        assertEquals("",hook("UserPromptSubmit","route","What configuration does it use?"));
        var mapper=new ObjectMapper();
        try(var files=Files.list(root.resolve(".quill/prefetch"))){var state=mapper.readTree(files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow().toFile());assertEquals("GET /warehouse/items/{key}",state.path("_endpoint").asText());}
        var missing=hook("UserPromptSubmit","route","GET /missing");assertTrue(missing.contains("endpoint_not_found"));
        assertFalse(missing.contains("Quill already ran these reads"));
        assertEquals("",hook("UserPromptSubmit","route","What configuration does it use?"));
        assertEquals("",hook("SessionStart","route"));
        // A reset drops the anchor; the new session's first untargeted prompt only gets the project shape.
        var restarted=hook("UserPromptSubmit","route","Continue");
        assertFalse(restarted.contains("endpoint_anchor"));assertTrue(restarted.contains("\\\"kind\\\":\\\"project\\\""),restarted);
        assertEquals("",hook("UserPromptSubmit","route","Continue"));
    }
}
