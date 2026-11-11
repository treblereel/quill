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
import org.treblereel.mcp.db.QuillDatabase;

class ConfigurationSourceEvidenceTest {
    @TempDir Path root;
    Jdbi db;
    static final ObjectMapper JSON=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        db=JavaEvidenceFixture.index(root,"package p; class Fixture {int active=1;int sibling=2; int fetch(){return active;} int other(){return sibling;}}");
        Files.createDirectories(root.resolve("src/main/resources"));
    }
    @AfterEach void close(){}
    Path file(String name,String text) throws Exception {Path p=root.resolve("src/main/resources/"+name);Files.writeString(p,text);return p;}
    void definition(String key,String kind,String name,int line) {
        db.useHandle(h->h.execute("INSERT INTO configuration_definitions(key,kind,file,line,module,source_set) VALUES (?,?,?,?,'.','main')",key,kind,"src/main/resources/"+name,line));
    }
    void usage(String key,String member) {
        db.useHandle(h->h.execute("INSERT INTO configuration_usages(key,kind,class_id,class_name,member,annotation,source,module,source_set) VALUES (?,'config_key',1,'p.Fixture',?,'org.eclipse.microprofile.config.inject.ConfigProperty','src/main/java/p/Fixture.java','.','main')",key,member));
    }
    JsonNode query(String key,String cls,String view) throws Exception {return JSON.readTree(new ConfigurationReferenceQueries().find(db,key,cls,"all",null,100,0,view));}
    @Test void metadataContractAndProfiledDefinitionsRemainSeparateFromEffectiveValues() throws Exception {
        file("application.properties","limits.timeout=5s\n%prod.limits.timeout=10s\n");
        definition("limits.timeout","property","application.properties",1);definition("%prod.limits.timeout","property","application.properties",2);usage("limits.timeout","active");
        var index=query("limits.timeout",null,"index");assertEquals(3,index.path("total").asInt(),index.toString());assertFalse(index.toString().contains("10s"));assertFalse(index.toString().contains("declared_source"));
        var declared=query("limits.timeout","Fixture","declared");assertEquals(2,declared.path("definition_count").asInt(),declared.toString());assertFalse(declared.path("effective_runtime_values_proven").asBoolean());
        Set<String> values=new HashSet<>(),profiles=new HashSet<>();
        for(var r:declared.path("references"))if(r.path("entry_type").asText().equals("definition")){
            values.add(r.path("declared_source").path("declared_value").asText());profiles.add(r.path("key_profile").asText());assertTrue(r.path("declared_source").path("source_complete").asBoolean());assertEquals(64,r.path("declared_source").path("source_sha256").asText().length());}
        assertEquals(2,query("%prod.limits.timeout","Fixture","declared").path("total").asInt());
        assertEquals(Set.of("5s","10s"),values);assertEquals(Set.of("default","prod"),profiles);
        assertTrue(query(null,null,"declared").has("error_code"));
    }
    @Test void expressionsSensitiveKeysAndOpaqueStringsAreNotExposed() throws Exception {
        String[] keys={"limits.password","limits.delay","limits.url","limits.enabled","limits.timeout","limits.size"};
        file("application.properties","limits.password=12345\nlimits.delay=${DELAY:HiddenDefault}\nlimits.url=jdbc://user:HiddenPassword@host\nlimits.enabled=true\nlimits.timeout=PT5S\nlimits.size=128Mi\n");
        for(int i=0;i<keys.length;i++)definition(keys[i],"property","application.properties",i+1);
        var data=query("limits.*",null,"declared");String text=data.toString();assertFalse(text.contains("Hidden"));assertFalse(text.contains("12345"));assertFalse(text.contains("${"));assertTrue(text.contains("redacted_key"));assertTrue(text.contains("expression_unresolved"));assertTrue(text.contains("opaque_or_unsupported_value"));assertTrue(text.contains("PT5S"));assertTrue(text.contains("128Mi"));
    }
    @Test void sharedParserHandlesContinuationEscapesYamlAndProfileFiles() throws Exception {
        file("application.properties","limits.delay=1\\\n0m\nlim\\u0069ts.count=7\n");definition("limits.delay","property","application.properties",1);definition("limits.count","property","application.properties",3);
        file("application-prod.yaml","\"%prod\":\n  limits:\n    enabled: true # note\n");definition("%prod.limits.enabled","yaml_property","application-prod.yaml",3);
        var data=query("limits.*",null,"declared");assertEquals(3,data.path("references").size());assertTrue(data.toString().contains("10m"));assertTrue(data.toString().contains("declared_value\":\"7"));
        var yaml=data.path("references").valueStream().filter(r->r.path("kind").asText().equals("yaml_property")).findFirst().orElseThrow();assertEquals("prod",yaml.path("file_profile").asText());assertEquals("prod",yaml.path("key_profile").asText());assertEquals("true",yaml.path("declared_source").path("declared_value").asText());
    }
    @Test void movedLinesOutsideSymlinksAndLargeFilesDoNotProduceValues() throws Exception {
        file("application.properties","# moved\nlimits.timeout=10s\n");definition("limits.timeout","property","application.properties",1);assertTrue(query("limits.timeout",null,"declared").toString().contains("definition_line_changed"));
        Path outside=Files.createTempFile("config-outside-",".properties");try{Files.writeString(outside,"limits.outside=99\n");Files.createSymbolicLink(root.resolve("src/main/resources/out.properties"),outside);definition("limits.outside","property","out.properties",1);assertTrue(query("limits.outside",null,"declared").toString().contains("source_outside_root"));}finally{Files.deleteIfExists(outside);}
        file("large.properties","limits.large=7\n"+"#".repeat(1_000_000));definition("limits.large","property","large.properties",1);assertTrue(query("limits.large",null,"declared").toString().contains("source_file_limit"));
    }
    @Test void sourceBudgetAndConcurrentMutationRemainExplicit() throws Exception {
        var content=new StringBuilder();for(int i=0;i<14;i++){content.append("k").append(i).append("=").append(i).append('\n');definition("k"+i,"property","application.properties",i+1);}Path p=file("application.properties",content.toString());
        var data=query("k*",null,"declared");assertEquals(14,data.path("references").size());assertEquals(2,data.path("references").valueStream().filter(r->r.path("declared_source").path("status").asText().equals("packet_source_limit")).count());
        ObjectNode result=JSON.createObjectNode();var ref=result.putArray("references").addObject();var source=TestReads.read(db, readScope -> new ConfigurationSourceEvidence(readScope));source.append(ref,"k0","src/main/resources/application.properties",1,"property");Files.writeString(p,"k0=9\n");source.finish(result);assertFalse(result.toString().contains("declared_value"));assertTrue(result.toString().contains("inputs_changed"));
    }
    @Test void missingAndDynamicDefinitionsStayUnknownAtRuntimeAndModuleFiltersRemain() throws Exception {
        usage("missing","active");
        db.useHandle(h->h.execute("INSERT INTO configuration_usages(key,kind,class_id,class_name,member,annotation,source,module,source_set) VALUES ('<dynamic>','dynamic_config_key',1,'p.Fixture','fetch','java.lang.System#getProperty','src/main/java/p/Fixture.java','.','main')"));
        var missing=query("missing",null,"declared");assertEquals("no_indexed_definition",missing.path("references").get(0).path("resolution_status").asText());assertFalse(missing.path("effective_runtime_values_proven").asBoolean());
        assertEquals("unknown",query("<dynamic>",null,"declared").path("references").get(0).path("resolution_status").asText());
        var other=JSON.readTree(new ConfigurationReferenceQueries().find(db,"missing",null,"all","other",100,0,"declared"));assertEquals(0,other.path("total").asInt());
    }
    @Test void persistenceUnitNamesAreNeverInterpretedAsProfiledProperties() throws Exception {
        definition("%prod.unit","persistence_unit","persistence.xml",1);
        db.useHandle(h->h.execute("INSERT INTO configuration_usages(key,kind,class_id,class_name,member,annotation,source,module,source_set) VALUES ('%prod.unit','persistence_unit',1,'p.Fixture','active','jakarta.persistence.PersistenceContext','Fixture.java','.','main')"));
        var data=JSON.readTree(new ConfigurationReferenceQueries().find(db,"%prod.unit",null,"persistence",null,100,0,"declared"));assertEquals(2,data.path("total").asInt(),data.toString());assertTrue(data.toString().contains("unsupported_source_kind"));assertFalse(data.toString().contains("key_profile"));
    }
    @Test void endpointConfigurationExcludesSiblingFields() throws Exception {
        file("application.properties","selected=1\nsibling=2\n");definition("selected","property","application.properties",1);definition("sibling","property","application.properties",2);usage("selected","active");usage("sibling","sibling");EndpointPrefetchTest.routes(db,"GET");
        var packet=TestReads.read(db, readScope -> EndpointPrefetch.collect(readScope,root,"GET /warehouse/items/{key}"));var refs=packet.path("facts").get(1).path("methods").get(0).path("configuration_references");assertEquals(1,refs.path("references").size(),refs.toString());assertEquals("active",refs.path("references").get(0).path("member").asText());assertFalse(refs.toString().contains("\"key\":\"sibling\""));
    }
    @Test void prefetchUsesExactIndexedKeysWithAnyWordingAndKeepsSourceSafety() throws Exception {
        file("application.properties","warehouse.cache-enabled=true\n%prod.warehouse.cache-enabled=false\nwarehouse.password=12345\n");
        definition("warehouse.cache-enabled","property","application.properties",1);
        definition("%prod.warehouse.cache-enabled","property","application.properties",2);
        definition("warehouse.password","property","application.properties",3);usage("warehouse.cache-enabled","active");
        var first=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Объясни `warehouse.cache-enabled`.")).path("facts");
        assertEquals(first,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Quelle valeur: warehouse.cache-enabled?")).path("facts"));
        assertEquals(1,first.size());var evidence=first.get(0).path("evidence");
        assertEquals("find_configuration_references",first.get(0).path("tool").asText());
        assertEquals("warehouse.cache-enabled",first.get(0).path("arguments").path("key").asText());
        var queried=(ObjectNode)JSON.readTree(new ConfigurationReferenceQueries().find(db,"warehouse.cache-enabled",null,"all",null,12,0,"declared"));
        queried.remove(java.util.List.of("_meta","configuration_resources"));ConfigurationEvidenceCompaction.compact(queried);var core=((ObjectNode)evidence).deepCopy();core.remove(java.util.List.of("field_annotations","related_configuration_declarations","configuration_resources"));assertEquals(core,queried);
        assertTrue(evidence.path("indexed_references_complete").asBoolean());assertEquals(3,evidence.path("references").size(),evidence.toString());
        assertFalse(evidence.path("effective_runtime_values_proven").asBoolean());assertTrue(evidence.toString().contains("declared_value"));
        assertTrue(TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"warehouse.cache warehouse.cache-enabled.extra")).path("facts").isEmpty());
        assertFalse(TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"warehouse.password")).toString().contains("12345"));
        assertTrue(TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"%prod.warehouse.cache-enabled")).path("facts").get(0).path("evidence").path("references").size()>0);
        Files.writeString(root.resolve("src/main/resources/application.properties"),"# moved\nwarehouse.cache-enabled=true\n");
        var changed=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"warehouse.cache-enabled"));
        assertFalse(changed.toString().contains("declared_value"));
    }
}
