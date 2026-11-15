package org.treblereel.mcp.mcp.config;

import org.treblereel.mcp.db.ReadQueryContext;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.semantic.JavaSemanticQueries;
import org.treblereel.mcp.mcp.TestReads;

class ConfigurationLocationsCoverageTest {
    @TempDir Path root;Jdbi db;ReadQueryContext reader;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        db=JavaEvidenceFixture.index(root,"package p; class Fixture {\n@Deprecated\nboolean flag;\nstatic final String KEY=\"warehouse.flag\";\nvoid read(){boolean flag=true;}\n}\n");
        var p=new Properties();p.setProperty("compilerSettingsProtocol","1");p.setProperty("evidence","successful_mojo");p.setProperty("sourceSet","main");p.setProperty("release","17");p.setProperty("jdkHome",System.getProperty("java.home"));
        var jdk=new Properties();try(var r=Files.newBufferedReader(Path.of(System.getProperty("java.home"),"release"))){jdk.load(r);}p.setProperty("jdkVersion",jdk.getProperty("JAVA_VERSION").replace("\"",""));
        p.setProperty("sourceRoot.count","1");p.setProperty("sourceRoot.0",root.resolve("src/main/java").toString());p.setProperty("compileClasspath.count","0");p.setProperty("argument.count","0");p.setProperty("buildInput.count","1");p.setProperty("buildInput.path.0",root.resolve("pom.xml").toString());p.setProperty("buildInput.sha256.0",SourceFileHashes.hash(root.resolve("pom.xml")));
        try(var w=Files.newBufferedWriter(root.resolve("target/quill-java-main.properties"))){p.store(w,"fixture");}
        Files.createDirectories(root.resolve("src/main/resources"));
        reader=ReadQueryContext.openWithGraph(db);
    }
    @AfterEach void close(){JavaSemanticQueries.close();reader.close();}
    @Test void compilerCoordinatesIncludeAnnotationsAndRequireExactFieldIdentity() throws Exception {
        var locations=new ConfigurationDeclarationLocations(reader);var field=json.createObjectNode();locations.append(field,"p.Fixture","flag","Z");
        assertEquals("compiler_declaration",field.path("location_status").asText(),field.toString());assertEquals(2,field.path("line").asInt());assertEquals(3,field.path("end_line").asInt());assertEquals(SourceFileHashes.hash(root.resolve("src/main/java/p/Fixture.java")),field.path("source_sha256").asText());
        var constant=json.createObjectNode();locations.append(constant,"p.Fixture","KEY","Ljava/lang/String;");assertEquals(4,constant.path("line").asInt());
        var wrong=json.createObjectNode();locations.append(wrong,"p.Fixture","flag","I");assertFalse(wrong.has("line"));
        wrong=json.createObjectNode().put("source","another-module/Fixture.java");locations.append(wrong,"p.Fixture","flag","Z");assertFalse(wrong.has("line"));assertEquals("ambiguous_source_owner",wrong.path("location_status").asText());
    }
    @Test void changedSourceNeverInventsCoordinates() throws Exception {
        Files.delete(root.resolve("target/quill-java-main.properties"));
        Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"// changed");var node=json.createObjectNode();new ConfigurationDeclarationLocations(reader).append(node,"p.Fixture","flag","Z");assertFalse(node.has("line"));assertEquals("source_hash_unavailable_or_changed",node.path("location_status").asText());
    }
    /** V34 CQ-040: without captured compiler settings a rename answer lacked every edit line, so each session reread the files. */
    @Test void withoutCompilerCaptureAUniqueHashCheckedFieldDeclarationGivesItsLines() throws Exception {
        Files.delete(root.resolve("target/quill-java-main.properties"));var locations=new ConfigurationDeclarationLocations(reader);
        var field=json.createObjectNode();locations.append(field,"p.Fixture","flag","Z");
        assertEquals("source_declaration",field.path("location_status").asText(),field.toString());assertEquals(2,field.path("line").asInt());assertEquals(3,field.path("end_line").asInt());
        assertTrue(locations.text(field).contains("@Deprecated"));assertFalse(locations.text(field).contains("read()"));
        var constant=json.createObjectNode();locations.append(constant,"p.Fixture","KEY","Ljava/lang/String;");assertEquals(4,constant.path("line").asInt(),constant.toString());
        var wrong=json.createObjectNode();locations.append(wrong,"p.Fixture","flag","I");assertFalse(wrong.has("line"),"a different type is not this field");
        var local=json.createObjectNode();locations.append(local,"p.Fixture","missing","Z");assertFalse(local.has("line"));
    }
    @Test void capturesBaseProfilesEscapesContinuationDuplicatesAndCheckedAbsenceWithoutValues() throws Exception {
        Files.writeString(root.resolve("src/main/resources/application.properties"),"warehouse.flag=SECRET_SENTINEL\n%prod.warehouse.flag=false\nwarehouse\\.flag=tr\\\n ue\n%default.warehouse.flag=false\n");
        Files.writeString(root.resolve("src/main/resources/application-dev.properties"),"unrelated.key=true\n");
        var result=TestReads.read(db, readScope -> ConfigurationResourceInventory.collect(readScope,"warehouse.flag"));assertEquals("ready",result.path("status").asText(),result.toString());var files=result.path("files");
        assertEquals(2,files.path("src/main/resources/application.properties").path("matches").path("default").size());assertEquals(2,files.path("src/main/resources/application.properties").path("matches").path("%prod").get(0).asInt());assertEquals(5,files.path("src/main/resources/application.properties").path("matches").path("%default").get(0).asInt());
        assertTrue(files.path("src/main/resources/application-dev.properties").path("matches").isEmpty());assertFalse(result.toString().contains("SECRET_SENTINEL"));assertFalse(result.toString().contains("unrelated.key"));
        var publicQuery=json.readTree(new ConfigurationReferenceQueries().find(db,"warehouse.flag",null,"all",null,12,0,"declared"));
        assertEquals(result,publicQuery.path("configuration_resources"));
        assertFalse(json.readTree(new ConfigurationReferenceQueries().find(db,"warehouse.flag",null,"all",null,12,0,"index")).has("configuration_resources"));
    }
    @Test void packingSharesOnlyEqualHashesAndDoesNotPromotePartialPages() {
        var e=json.createObjectNode();var ref=e.putArray("references").addObject().put("source","A.java").put("line",3).put("end_line",4).put("location_status","compiler_declaration").put("source_sha256","same");
        var file=e.putObject("field_read_source").putArray("files").addObject().put("file","A.java").put("source_sha256","different");file.putArray("windows").addObject().put("source","int code(){return 1;}");
        ConfigurationDeclarationLocations.compact(e);assertEquals("same",ref.path("source_sha256").asText());assertEquals(3,ref.path("line").asInt());assertFalse(e.has("indexed_references_complete"));
        ref.put("location_status","compiler_declaration");file.put("source_sha256","same");ConfigurationDeclarationLocations.compact(e);assertFalse(ref.has("source_sha256"));assertEquals("int code(){return 1;}",file.path("windows").get(0).path("source").asText());
    }
    @Test void malformedUnsupportedLargeAndSymlinkResourcesStayUnknown() throws Exception {
        Files.writeString(root.resolve("src/main/resources/application.properties"),"bad=\\uZZZZ\n");Files.writeString(root.resolve("src/main/resources/application.yml"),"warehouse: {flag: true}\n");Files.writeString(root.resolve("src/main/resources/.env"),"NO_READ_SENTINEL");
        Files.writeString(root.resolve("src/main/resources/large.properties"),"x".repeat(1_000_001));
        Path outside=Files.createTempFile("quill-outside-",".properties");try {
            Files.writeString(outside,"warehouse.flag=NEVER_READ");Files.createSymbolicLink(root.resolve("src/main/resources/link.properties"),outside);
            var result=TestReads.read(db, readScope -> ConfigurationResourceInventory.collect(readScope,"warehouse.flag"));assertEquals("partial",result.path("status").asText());var files=result.path("files");
            for(String name:List.of("application.properties","application.yml",".env","large.properties")){var file=files.path("src/main/resources/"+name);assertEquals("unknown",file.path("status").asText(),result.toString());assertFalse(file.has("matches"));}
            assertFalse(result.toString().contains("NEVER_READ"));assertFalse(result.toString().contains("NO_READ_SENTINEL"));assertFalse(files.has("src/main/resources/link.properties"));
        }finally{Files.deleteIfExists(outside);}
    }
}
