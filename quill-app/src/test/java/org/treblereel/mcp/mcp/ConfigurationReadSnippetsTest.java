package org.treblereel.mcp.mcp;

import org.treblereel.mcp.db.ReadQueryContext;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationReadSnippetsTest {
    @TempDir Path root;
    Jdbi db;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {db=JavaEvidenceFixture.index(root,"package p; class Fixture {\nboolean enabled;\nboolean read(){\n if (!enabled) return false;\n return true;\n}\n}\n");}
    ObjectNode snippets(Map<String,SortedSet<Integer>> lines){var node=json.createObjectNode();ReadQueryContext.readWithGraph(db, readScope -> { ConfigurationReadSnippets.append(readScope,node,lines); return null; });return node;}
    @Test void adjacentWindowsMergeAndContainGuardAndActionWithoutClaimingCompleteMethod() {
        var result=snippets(Map.of("src/main/java/p/Fixture.java",new TreeSet<>(List.of(4,5))));var data=result.path("field_read_source");
        assertEquals("ready",data.path("status").asText(),result.toString());var windows=data.path("files").get(0).path("windows");assertEquals(1,windows.size());
        assertTrue(windows.get(0).path("source").asText().contains("if (!enabled) return false;"));assertEquals(1,windows.get(0).path("start_line").asInt());
        assertEquals(64,data.path("files").get(0).path("source_sha256").asText().length());assertFalse(data.path("method_complete").asBoolean());assertFalse(data.path("source_bytecode_identity_proven").asBoolean());
    }
    @Test void changedUnhashedOutsideAndNonJavaSourcesNeverExposeText() throws Exception {
        Path outside=Files.createTempFile("quill-snippet-outside-",".java");try {
            Files.writeString(outside,"OUTSIDE_SENTINEL");
            var escaped=snippets(Map.of(outside.toString(),new TreeSet<>(List.of(1))));
            assertFalse(escaped.toString().contains("OUTSIDE_SENTINEL"));assertTrue(escaped.toString().contains("source_outside_root"));
        }finally{Files.delete(outside);}
        var unsupported=snippets(Map.of("source.kt",new TreeSet<>(List.of(1))));assertFalse(unsupported.toString().contains("\"source\":"));
        String source="src/main/java/p/Fixture.java";Files.writeString(root.resolve(source),"// changed PRIVATE_SENTINEL\n");
        assertFalse(snippets(Map.of(source,new TreeSet<>(List.of(1)))).toString().contains("PRIVATE_SENTINEL"));
    }
    @Test void invalidAndMissingLinesDoNotInventLocations() {
        var data=snippets(Map.of("src/main/java/p/Fixture.java",new TreeSet<>(List.of(0,Integer.MAX_VALUE))));
        assertTrue(data.path("field_read_source").path("files").isEmpty());assertEquals(2,data.path("field_read_source").path("unavailable").size());
        assertFalse(snippets(Map.of()).has("field_read_source"));
    }
    @Test void limitsRemainExplicit() throws Exception {
        String source="package p;class Fixture {\n"+"// long line "+"x".repeat(10000)+"\n}\n";
        db=JavaEvidenceFixture.index(root.resolve("large"),source);
        var data=snippets(Map.of("src/main/java/p/Fixture.java",new TreeSet<>(List.of(2)))).path("field_read_source");assertTrue(data.path("truncated").asBoolean(),data.toString());
        assertTrue(data.toString().length()<10000);assertTrue(data.path("files").get(0).path("windows").isEmpty());assertEquals("partial",data.path("status").asText());
    }
    @Test void globalLineAndWindowBudgetsAreNotPerRead() throws Exception {
        db=JavaEvidenceFixture.index(root.resolve("wide"),"package p;class Fixture {\n"+"// context\n".repeat(200)+"}\n");
        SortedSet<Integer> dense=new TreeSet<>();for(int n=5;n<195;n+=5)dense.add(n);
        var data=snippets(Map.of("src/main/java/p/Fixture.java",dense)).path("field_read_source");
        assertTrue(data.path("truncated").asBoolean());var ranges=data.path("files").get(0).path("windows");assertEquals(1,ranges.size());
        assertEquals(80,ranges.get(0).path("end_line").asInt()-ranges.get(0).path("start_line").asInt()+1);
        var sparse=new TreeSet<>(List.of(10,30,50,70,90,110,130,150,170,190));
        data=snippets(Map.of("src/main/java/p/Fixture.java",sparse)).path("field_read_source");assertEquals(8,data.path("files").get(0).path("windows").size());assertTrue(data.path("truncated").asBoolean());
    }

}
