package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.QuillDatabase;

class JavaSourceLocationsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    Jdbi db; Path file, capture;
    String relative = "src/main/java/p/Example.java";
    @BeforeEach void fixture() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        file = root.resolve(relative); Files.createDirectories(file.getParent());
        Files.writeString(file, "package p;\nclass Example {\n int value;\n int pick(int x){return x;} int pick(String x){return 1;}\n"
                + " void run(){pick(1); pick(2); pick(\"x\"); value=1; value++;}\n}");
        Properties settings = new Properties();
        settings.setProperty("compilerSettingsProtocol", "1"); settings.setProperty("evidence", "successful_mojo");
        settings.setProperty("sourceSet", "main"); settings.setProperty("release", "17");
        settings.setProperty("jdkHome", System.getProperty("java.home"));
        var jdk = new Properties(); try (var reader = Files.newBufferedReader(Path.of(System.getProperty("java.home"), "release"))) { jdk.load(reader); }
        settings.setProperty("jdkVersion", jdk.getProperty("JAVA_VERSION").replace("\"", ""));
        settings.setProperty("sourceRoot.count", "1"); settings.setProperty("sourceRoot.0", root.resolve("src/main/java").toString());
        settings.setProperty("compileClasspath.count", "0"); settings.setProperty("argument.count", "0");
        settings.setProperty("buildInput.count", "1"); settings.setProperty("buildInput.path.0", root.resolve("pom.xml").toString());
        settings.setProperty("buildInput.sha256.0", SourceFileHashes.hash(root.resolve("pom.xml")));
        capture = root.resolve("target/quill-java-main.properties"); Files.createDirectories(capture.getParent());
        try (var writer = Files.newBufferedWriter(capture)) { settings.store(writer, "test"); }
        db = QuillDatabase.create(root.resolve("index.db"));
        metadata("project_root", root.toString());
        metadata("java_source_hashes", JSON.writeValueAsString(Map.of(relative, SourceFileHashes.hash(file))));
        db.useHandle(h -> {
            h.execute("INSERT INTO classes(id,class_name,kind,source_file,source_line,is_bean,source_tokens) VALUES(1,'p.Example','CLASS',?,2,0,100)", relative);
            h.execute("""
                INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations)
                VALUES(1,'METHOD','pick','pick(int):int','(I)I','int','["int"]','','[]'),
                      (1,'FIELD','value','value:int','I','int','[]','','[]')
                """);
            h.execute("""
                INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind,occurrence_count,evidence_lines)
                VALUES(1,'run','()V',1,'pick','(I)I','virtual',2,'[5]')
                """);
            h.execute("""
                INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind,occurrence_count,evidence_lines)
                VALUES(1,'run','()V',1,'value','I','write_instance',2,'[5]'),
                      (1,'run','()V',1,'value','I','read_instance',1,'[5]')
                """);
        });
    }
    void metadata(String key, String value) { db.useHandle(h -> h.execute("INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)", key, value)); }
    @AfterEach void stop() { JavaSemanticQueries.close(); }
    ObjectNode usages(String name, String kind, String access) throws Exception {
        return (ObjectNode) JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,"p.Example",name,kind,null,access,10,0));
    }
    @Test void methodAndFieldResponsesContainOnlyExactCompilerCorroboratedRanges() throws Exception {
        var result = usages("pick", "method", "all");
        var evidence = result.path("usages").get(0).path("source_evidence");
        assertEquals("corroborated", evidence.path("status").asText(), result.toString());
        assertFalse(evidence.path("bytecode_source_identity_proven").asBoolean());
        assertEquals(2, evidence.path("locations").size());
        assertEquals(result.path("symbol_id").asText(), evidence.path("locations").get(0).path("symbol_id").asText());
        var line = Files.readAllLines(file).get(4);
        for (var location : evidence.path("locations")) {
            assertEquals(5, location.path("line").asInt());
            assertEquals("pick", line.substring(location.path("column").asInt()-1, location.path("end_column").asInt()-1));
        }
        assertEquals(2, usages("value","field","write").path("usages").get(0).path("source_evidence").path("locations").size());
        assertEquals(1, usages("value","field","read").path("usages").get(0).path("source_evidence").path("locations").size());
        var hierarchy = JSON.readTree(new CallHierarchyQueries().getCallHierarchy(db,"p.Example","pick","(I)I","inbound",false,1,10,0));
        assertEquals(2, hierarchy.path("calls").get(0).path("source_evidence").path("locations").size());
    }
    @Test void changedSourceWithRestoredTimestampRetainsOnlyBytecodeEvidence() throws Exception {
        var time = Files.getLastModifiedTime(file);
        Files.writeString(file, Files.readString(file).replace("pick(1)", "pick(3)")); Files.setLastModifiedTime(file,time);
        var usage = usages("pick","method","all").path("usages").get(0);
        assertEquals("source_changed_after_index", usage.path("source_evidence").path("status").asText());
        assertEquals(1, usage.path("evidence_lines").size());
        assertFalse(usage.path("source_evidence").has("locations"));
    }
    @Test void oldIndexesMissingCapturesAndInvalidHashEvidenceRemainExplicit() throws Exception {
        db.useHandle(h -> h.execute("DELETE FROM metadata WHERE key='java_source_hashes'"));
        assertEquals("indexed_source_hash_unavailable", usages("pick","method","all").path("usages").get(0).path("source_evidence").path("status").asText());
        metadata("java_source_hashes","null");
        assertEquals("indexed_source_hash_unavailable", usages("pick","method","all").path("usages").get(0).path("source_evidence").path("status").asText());
        metadata("java_source_hashes",JSON.writeValueAsString(Map.of(relative,SourceFileHashes.hash(file))));
        Files.delete(capture);
        assertEquals("source_set_unknown", usages("pick","method","all").path("usages").get(0).path("source_evidence").path("status").asText());
    }
    @Test void wrongCallerDescriptorOrBytecodeLineCannotProduceSourceCoordinates() throws Exception {
        var node = JSON.createObjectNode();
        var locations = new JavaSourceLocations(db);
        locations.append(node,relative,"p.Example","run","(I)V","p.Example","pick","(I)I","call","id",List.of(5));
        assertEquals("no_matching_source_reference",node.path("source_evidence").path("status").asText());
        locations.append(node,relative,"p.Example","run","()V","p.Example","pick","(I)I","call","id",List.of(4));
        assertTrue(node.path("source_evidence").path("locations").isEmpty());
    }
}
