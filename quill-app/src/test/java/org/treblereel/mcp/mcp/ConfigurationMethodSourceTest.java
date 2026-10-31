package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;

class ConfigurationMethodSourceTest {
    @TempDir Path root;Jdbi db;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        db=JavaEvidenceFixture.index(root,"package p; class Fixture {\nboolean enabled;\nboolean read(int n){\n if (!enabled) return false;\n String braces=\"} /* not a brace */\";\n return n>0;\n}\nboolean read(String n){return enabled && !n.isEmpty();}\n}\n");
        var p=new Properties();p.setProperty("compilerSettingsProtocol","1");p.setProperty("evidence","successful_mojo");p.setProperty("sourceSet","main");p.setProperty("release","17");p.setProperty("jdkHome",System.getProperty("java.home"));
        var jdk=new Properties();try(var r=Files.newBufferedReader(Path.of(System.getProperty("java.home"),"release"))){jdk.load(r);}p.setProperty("jdkVersion",jdk.getProperty("JAVA_VERSION").replace("\"",""));
        p.setProperty("sourceRoot.count","1");p.setProperty("sourceRoot.0",root.resolve("src/main/java").toString());p.setProperty("compileClasspath.count","0");p.setProperty("argument.count","0");p.setProperty("buildInput.count","1");p.setProperty("buildInput.path.0",root.resolve("pom.xml").toString());p.setProperty("buildInput.sha256.0",SourceFileHashes.hash(root.resolve("pom.xml")));
        try(var w=Files.newBufferedWriter(root.resolve("target/quill-java-main.properties"))){p.store(w,"fixture");}
    }
    @AfterEach void close(){JavaSemanticQueries.close();}
    ObjectNode evidence(){
        var e=json.createObjectNode();var r=e.putArray("references").addObject().put("className","p.Fixture");var sites=r.putObject("field_reads").put("truncated",false).putArray("sites");
        sites.addObject().put("method","read").put("descriptor","(I)Z").putArray("lines").add(4);
        sites.addObject().put("method","read").put("descriptor","(Ljava/lang/String;)Z").putArray("lines").add(8);
        ConfigurationReadSnippets.append(db,e,Map.of("src/main/java/p/Fixture.java",new TreeSet<>(List.of(4,8))));return e;
    }
    @Test void returnsWholeExactOverloadsNotBraceHeuristics() {
        var e=evidence();ConfigurationMethodSource.append(db,e);var s=e.path("field_read_source");assertTrue(s.path("method_complete").asBoolean(),e.toString());assertFalse(s.path("source_bytecode_identity_proven").asBoolean());
        var w=s.path("files").get(0).path("windows");assertEquals(2,w.size());assertTrue(w.get(0).path("source").asText().contains("return n>0;"));assertEquals(3,w.get(0).path("start_line").asInt());assertEquals(7,w.get(0).path("end_line").asInt());assertTrue(w.get(1).path("source").asText().startsWith("boolean read(String n)"));
    }
    @Test void missingCompilerCaptureKeepsHonestBoundedFallback() throws Exception {
        Files.delete(root.resolve("target/quill-java-main.properties"));var e=evidence();var before=e.deepCopy();ConfigurationMethodSource.append(db,e);assertEquals(before,e);assertFalse(e.path("field_read_source").path("method_complete").asBoolean());
    }
    @Test void truncationOrWrongReadRangeNeverClaimsCompleteCoverage() {
        var e=evidence();((ObjectNode)e.path("references").get(0).path("field_reads")).put("truncated",true);ConfigurationMethodSource.append(db,e);assertFalse(e.path("field_read_source").path("method_complete").asBoolean());
        e=evidence();((ArrayNode)e.path("references").get(0).path("field_reads").path("sites").get(0).path("lines")).add(99);ConfigurationMethodSource.append(db,e);assertFalse(e.path("field_read_source").path("method_complete").asBoolean());
    }
    @Test void hookBudgetOmitsWholeFactsAndNamesMissingCoverage() {
        var data=json.createObjectNode().put("status","ready");var facts=data.putArray("facts");
        var small=facts.addObject().put("kind","configuration").put("tool","find_configuration_references");small.putObject("arguments").put("key","warehouse.enabled");small.putObject("evidence").put("source","complete small source");
        var large=facts.addObject().put("kind","configuration").put("tool","find_configuration_references");large.putObject("arguments").put("key","warehouse.large");large.putObject("evidence").put("source","λ".repeat(6000));
        var original=small.deepCopy();PrefetchContext.boundContext(data);
        assertEquals(1,facts.size());assertEquals(original,facts.get(0));assertEquals("warehouse.large",data.path("unavailable").get(0).path("arguments").path("key").asText());
        assertEquals("partial",data.path("status").asText());assertTrue(data.path("context_truncated").asBoolean());assertTrue(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=10000);
        var before=data.deepCopy();PrefetchContext.boundContext(data);assertEquals(before,data);
    }
    @Test void changedSourceCannotLeaveOldSnippetInFallback() throws Exception {
        var e=evidence();Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"// CHANGED_SOURCE_SENTINEL");ConfigurationMethodSource.append(db,e);
        assertTrue(e.path("field_read_source").path("files").isEmpty());assertFalse(e.toString().contains("CHANGED_SOURCE_SENTINEL"));
    }
}
