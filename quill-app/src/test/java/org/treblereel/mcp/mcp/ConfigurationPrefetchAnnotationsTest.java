package org.treblereel.mcp.mcp;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class ConfigurationPrefetchAnnotationsTest {
    @TempDir Path root;Jdbi db;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        db=JavaEvidenceFixture.index(root,Map.of(
          "src/main/java/org/eclipse/microprofile/config/inject/ConfigProperty.java","package org.eclipse.microprofile.config.inject;import java.lang.annotation.*;@Retention(RetentionPolicy.RUNTIME)@Target({ElementType.FIELD,ElementType.METHOD})public @interface ConfigProperty {String name();}",
          "src/main/java/p/Fixture.java","package p; import org.eclipse.microprofile.config.inject.ConfigProperty; class Fixture {@ConfigProperty(name=\"warehouse.enabled\") boolean enabled; @Deprecated @ConfigProperty(name=\"warehouse.cache\") boolean cache; @ConfigProperty(name=\"warehouse.write\") boolean writeOnly; boolean read(){return enabled && cache;} void write(){writeOnly=true;} }") );
        Files.createDirectories(root.resolve("src/main/resources"));Files.writeString(root.resolve("src/main/resources/application.properties"),"warehouse.enabled=true\nwarehouse.cache=false\n");
        db.useHandle(h->h.execute("INSERT INTO configuration_definitions(key,kind,file,line,module,source_set) VALUES ('warehouse.cache','property','src/main/resources/application.properties',2,'.','main')"));
    }
    ObjectNode query() throws Exception{return (ObjectNode)json.readTree(new ConfigurationReferenceQueries().find(db,"warehouse.enabled",null,"all",null,12,0,"declared"));}
    @Test void groupsExactReadFieldAnnotationsAndRelatedDeclaredValues() throws Exception {
        var e=query();ConfigurationPrefetchAnnotations.append(db,e);var c=e.path("field_annotations");assertEquals("ready",c.path("status").asText(),e.toString());
        var a=e.path("references").get(0).path("field_annotations");var bindings=a.path("org.eclipse.microprofile.config.inject.ConfigProperty");
        assertEquals("warehouse.enabled",bindings.path("enabled").get(0).asText());assertEquals("warehouse.cache",bindings.path("cache").get(0).asText());assertFalse(bindings.has("writeOnly"));
        assertTrue(a.path("java.lang.Deprecated").has("cache"));assertTrue(c.path("scope").asText().contains("unknown"));
        var v=e.path("related_configuration_declarations").get(0);assertEquals("warehouse.cache",v.path("key").asText());assertEquals("false",v.path("declared_source").path("declared_value").asText());assertFalse(v.path("declared_source").path("effective_value_proven").asBoolean());
    }
    @Test void wrongDescriptorAndWriteOnlyAccessNeverInventAnnotations() throws Exception {
        db.useHandle(h->h.execute("UPDATE field_accesses SET field_descriptor='I' WHERE field_name='cache'"));var e=query();ConfigurationPrefetchAnnotations.append(db,e);
        var t=e.path("field_annotations").toString();assertFalse(t.contains("warehouse.cache"));assertFalse(t.contains("warehouse.write"));assertTrue(e.path("related_configuration_declarations").isEmpty());
    }
    @Test void inventoryReportsEmptyAndProfileFilesWithoutReadingValues() throws Exception {
        Files.createDirectories(root.resolve("src/test/resources"));Files.writeString(root.resolve("src/main/resources/application-prod.properties"),"");Files.writeString(root.resolve("src/test/resources/custom.yaml"),"password: PRIVATE_VALUE_SENTINEL");Files.writeString(root.resolve("outside.properties"),"ignored=true");
        var e=ConfigurationResourceInventory.collect(db);assertEquals("ready",e.path("status").asText(),e.toString());assertEquals(3,e.path("files").size());assertTrue(e.toString().contains("application-prod.properties"));assertFalse(e.toString().contains("PRIVATE_VALUE_SENTINEL"));assertFalse(e.toString().contains("outside.properties"));assertTrue(e.path("scope").asText().contains("unproven"));
    }
    @Test void inventoryDoesNotFollowSymlinksOrPretendCompleteCoverage() throws Exception {
        Path outside=Files.createTempDirectory("quill-config-inventory-");try{Files.writeString(outside.resolve("outside.yml"),"PRIVATE");Files.createSymbolicLink(root.resolve("src/main/resources/link"),outside);var e=ConfigurationResourceInventory.collect(db);assertEquals("partial",e.path("status").asText());assertFalse(e.toString().contains("outside.yml"));assertTrue(e.toString().contains("symlink_not_followed"));}finally{Files.deleteIfExists(outside.resolve("outside.yml"));Files.deleteIfExists(outside);}
    }
    @Test void relatedDeclarationsShareProvenanceOnlyWhenFileKindAndHashesMatch() {
        var e=json.createObjectNode();var primary=e.putArray("references").addObject().put("file","application.properties").put("kind","property");primary.putObject("declared_source").put("source_sha256","same-source");
        var related=e.putArray("related_configuration_declarations");var yes=related.addObject().put("key","other.one").put("line",2).put("file","application.properties").put("kind","property");yes.putObject("declared_source").put("source_sha256","same-source").put("declared_value","false");
        var no=related.addObject().put("key","other.two").put("file","application.properties").put("kind","property");no.putObject("declared_source").put("source_sha256","different-source");
        ConfigurationPrefetchAnnotations.compact(e);assertEquals("other.one",primary.path("related_declarations").get(0).path("key").asText());assertFalse(primary.path("related_declarations").get(0).path("declared_source").has("source_sha256"));assertEquals("other.two",e.path("related_configuration_declarations").get(0).path("key").asText());assertTrue(e.has("related_declaration_provenance"));
    }
    @Test void methodLayoutNormalizationPreservesRelativeIndentationIncludingTextBlocks() {
        String source="String value(){\n    String s = \"\"\"\n        a\n          b\n        \"\"\";\n    return s;\n    }";
        String dedented=ConfigurationMethodSource.dedent(source);assertTrue(dedented.contains("\n    a\n      b\n    \"\"\";"));assertEquals(source.lines().count(),dedented.lines().count());
        String unindented="String value(){\n    String s = \"\"\"\n  raw\n\"\"\";\n    return s;\n}";assertEquals(unindented,ConfigurationMethodSource.dedent(unindented));
    }
    @Test void inventoryLimitsAreGlobalAndVisible() throws Exception {
        for(int n=0;n<70;n++)Files.writeString(root.resolve("src/main/resources/setting"+n+".properties"),"");var e=ConfigurationResourceInventory.collect(db);assertTrue(e.path("truncated").asBoolean());assertTrue(e.path("files").size()<=64);assertEquals("partial",e.path("status").asText());
    }
}
