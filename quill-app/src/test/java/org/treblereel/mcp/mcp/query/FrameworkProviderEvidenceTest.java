package org.treblereel.mcp.mcp.query;

import org.treblereel.mcp.db.IndexReader;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.*;
import org.treblereel.mcp.mcp.TestReads;

class FrameworkProviderEvidenceTest {
    @TempDir Path root;
    Jdbi db;
    private static final ObjectMapper JSON = new ObjectMapper();
    @BeforeEach void fixture() {
        db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                new ClassRecord(0,"x.Consumer","CLASS","java.lang.Object",List.of(),"Consumer.java",1,true,10),
                new ClassRecord(0,"x.Message","INTERFACE",null,List.of(),"Message.java",1,false,10),
                new ClassRecord(0,"x.Factory","CLASS","java.lang.Object",List.of(),"Factory.java",1,true,10),
                new ClassRecord(0,"x.Settings","INTERFACE",null,List.of(),"Settings.java",1,false,10)))
                .beans(List.of(
                        new BeanRecord(0,1,"CLASS","@Singleton",List.of("@Default"),List.of(),false,null,null,null,null,List.of("x.Consumer")),
                        new BeanRecord(0,3,"CLASS","@Singleton",List.of("@Default"),List.of(),false,null,null,null,null,List.of("x.Factory")),
                        new BeanRecord(0,3,"PRODUCER_METHOD","@Dependent",List.of("@Default"),List.of(),false,null,List.of("+prod"),3,"make",List.of("x.Message"))))
                .injectionPoints(List.of(
                        InjectionPointRecord.staticAnalysis(0,1,"FIELD","x.Message",List.of("@Default"),"message",null,false,InjectionPointRecord.STATIC_CDI),
                        InjectionPointRecord.staticAnalysis(0,1,"FIELD","x.Settings",List.of("@Default"),"settings",null,false,InjectionPointRecord.STATIC_CDI)))
                .dependencies(List.of())
                .metadata(Map.of("dependency_index","complete")).snapshot());
        db.useHandle(h -> {
            h.execute("UPDATE classes SET module='app',source_set='main'");
            h.execute("INSERT INTO class_annotations(class_id,annotation_name,direct) VALUES (4,?,1)","io.smallrye.config.ConfigMapping");
        });
        IndexFixture.writeExternalBeans(db,List.of(
                provider(1,"dep.Factory","CLASS","@Singleton",null,List.of("dep.Factory"),List.of("@Default"),List.of()),
                provider(2,"dep.Factory","PRODUCER_METHOD","@Dependent","create",List.of("x.Message"),List.of("@Default"),List.of("+prod")),
                provider(3,"dep.Factory","PRODUCER_METHOD","@Dependent","named",List.of("x.Message"),List.of("@dep.Named"),List.of("+test")),
                provider(4,"dep.StrategyMessage","CLASS","@Singleton",null,List.of("dep.StrategyMessage"),List.of("@Default"),List.of())));
    }
    @AfterEach void close() {  }
    private static ExternalBeanRecord provider(int id,String cls,String kind,String scope,String member,List<String> types,List<String> qualifiers,List<String> profiles) {
        return new ExternalBeanRecord(id,cls,kind,scope,qualifiers,List.of(),false,false,null,profiles,member,types,"cdi","test:providers:1.0","/tmp/providers.jar",List.of());
    }
    private JsonNode beans(String name,String scope,String profile,String qualifier,String origin,String module,int limit,int offset) throws Exception {
        return JSON.readTree(TestReads.<String>read(db, readScope -> new StructureToolQueries().getBeans(readScope,name,scope,null,profile,qualifier,module,null,origin,limit,offset)));
    }
    @Test void exactTypeFallbackDoesNotMatchUnrelatedClassSuffix() throws Exception {
        var shortName=beans("Message",null,null,null,"dependency",null,10,0);
        var qualified=beans("x.Message",null,null,null,"dependency",null,10,0);
        assertEquals(2,shortName.path("total").asInt(),shortName.toString());
        assertEquals(shortName.path("beans"),qualified.path("beans"));
        assertEquals("provided_bean_type",shortName.path("lookup_kind").asText());
        assertFalse(shortName.path("runtime_resolution_proven").asBoolean());
        assertFalse(shortName.toString().contains("StrategyMessage"));
        assertEquals(1,beans("*Message",null,null,null,"dependency",null,10,0).path("total").asInt());
        var page=beans("Message",null,null,null,"dependency",null,1,0);
        assertTrue(page.path("has_more").asBoolean());
        assertEquals(1,beans("Message",null,null,null,"dependency",null,1,1).path("beans").size());
    }
    @Test void typeSearchPreservesOriginProfileQualifierScopeAndApplicationModule() throws Exception {
        var result=beans("Message","@Dependent","prod","Default","dependency",null,10,0);
        assertEquals(1,result.path("total").asInt());
        assertEquals("create",result.path("beans").get(0).path("member").asText());
        assertEquals(0,beans("Message","@Singleton","prod",null,"dependency",null,10,0).path("total").asInt());
        assertEquals(1,beans("Message",null,"prod",null,"application","app",10,0).path("total").asInt());
        assertEquals(0,beans("Message",null,"test",null,"application","app",10,0).path("total").asInt());
        assertEquals(0,beans("Message",null,"prod",null,"application","other",10,0).path("total").asInt());
        var byOwner=beans("dep.Factory",null,null,null,"dependency",null,10,0);
        assertEquals(3,byOwner.path("total").asInt());
        assertEquals("@Singleton",byOwner.path("beans").get(0).path("scope").asText());
        assertEquals("@Dependent",byOwner.path("beans").get(1).path("scope").asText());
        assertEquals("CLASS_NOT_FOUND",beans("Missing",null,null,null,"all",null,10,0).path("error_code").asText());
    }
    @Test void unknownInjectionReportsOrdinaryProducerAndMappingDeclarationWithoutRuntimeOrScopeInference() throws Exception {
        var result=JSON.readTree(TestReads.<String>read(db, readScope -> new StructureToolQueries().getInjectionPoints(readScope,"Consumer")));
        var point=result.path("injection_points").get(0);
        assertEquals("unknown",point.path("resolution").asText());
        assertFalse(point.has("resolved_scope"));
        var evidence=point.path("provider_evidence");
        assertEquals(2,evidence.path("total").asInt());
        assertFalse(evidence.path("runtime_resolution_proven").asBoolean());
        var producer=evidence.path("declarations").get(0);
        assertEquals("PRODUCER_METHOD",producer.path("kind").asText());
        assertEquals("cdi",producer.path("framework").asText());
        assertEquals("@Dependent",producer.path("bean_scope").asText());
        assertEquals("test:providers:1.0",producer.path("artifact").asText());
        assertTrue(producer.path("qualifier_names_match").asBoolean());
        assertFalse(evidence.path("declarations").get(1).path("qualifier_names_match").asBoolean());
        // V66 CQ-050: with CacheProducer#produce the only match, Codex read its bytecode to learn what supplies @CacheName Cache.
        String single=evidence.path("single_match").asText();
        assertTrue(single.startsWith(producer.path("declaring_class").asText()+"#"+producer.path("member").asText()+" (PRODUCER_METHOD @Dependent"),single);
        assertTrue(single.contains("no application bean is a candidate"),single);
        var mapping=result.path("injection_points").get(1).path("provider_evidence");
        assertEquals(0,mapping.path("total").asInt());
        assertEquals("not_proven",mapping.path("configuration_mapping_declaration").path("generated_bean_scope").asText());
        assertFalse(mapping.path("runtime_resolution_proven").asBoolean());
    }
    @Test void sameShortProvidedTypeKeepsEveryNamespaceAndEvidencePaginationIsExplicit() throws Exception {
        var all=new java.util.ArrayList<ExternalBeanRecord>();
        for(int i=1;i<=6;i++)all.add(provider(i,"dep.Factory"+i,"PRODUCER_METHOD","@Dependent","make",List.of(i==6?"y.Message":"x.Message"),List.of("@Default"),List.of()));
        IndexFixture.writeExternalBeans(db,all);
        assertEquals(6,beans("Message",null,null,null,"dependency",null,10,0).path("total").asInt());
        assertEquals(5,beans("x.Message",null,null,null,"dependency",null,10,0).path("total").asInt());
        var point=JSON.readTree(TestReads.<String>read(db, readScope -> new StructureToolQueries().getInjectionPoints(readScope,"Consumer"))).path("injection_points").get(0);
        assertEquals(5,point.path("provider_evidence").path("total").asInt());
        IndexFixture.writeExternalBeans(db,all.stream().map(p -> provider(p.id(),p.className(),p.kind(),p.scope(),p.memberName(),List.of("x.Message"),p.qualifiers(),p.profiles())).toList());
        var evidence=JSON.readTree(TestReads.<String>read(db, readScope -> new StructureToolQueries().getInjectionPoints(readScope,"Consumer"))).path("injection_points").get(0).path("provider_evidence");
        assertEquals(6,evidence.path("total").asInt());assertEquals(5,evidence.path("showing").asInt());assertTrue(evidence.path("has_more").asBoolean());
        assertFalse(evidence.has("single_match"),evidence.toString());
    }
    @Test void literalNamesDoNotEraseGenericsOrUseSqlUnderscoreAsWildcard() throws Exception {
        assertFalse(IndexReader.matchesBeanType(List.of("dep.OtherMessage"),"Message"));
        assertFalse(IndexReader.matchesBeanType(List.of("y.Message"),"x.Message"));
        assertFalse(IndexReader.matchesBeanType(List.of("x.Box<java.lang.String>"),"Box"));
        assertTrue(IndexReader.matchesBeanType(List.of("x.Outer$Inner"),"Inner"));
        IndexFixture.writeExternalBeans(db,List.of(provider(1,"dep.FactoryXNode","CLASS","@Singleton",null,List.of("dep.FactoryXNode"),List.of(),List.of())));
        assertEquals(0,beans("Factory_Node",null,null,null,"dependency",null,10,0).path("total").asInt());
    }
}
