package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.KotlinDeclarationTypes;

class ApiCompatibilityQueriesTest {
    @TempDir Path root;
    Jdbi before,after;
    @BeforeEach void setup() {before=QuillDatabase.create(root.resolve("before.db"));after=QuillDatabase.create(root.resolve("after.db"));owner(before,"p.Api","main");owner(after,"p.Api","main");}
    int owner(Jdbi db,String name,String set) {
        return db.withHandle(h->h.createUpdate("INSERT INTO classes(class_name,kind,superclass,interfaces,origin,lifecycle,module,source_set) VALUES (:name,'CLASS','java.lang.Object','[]','source','current','app',:set)")
                .bind("name",name).bind("set",set).executeAndReturnGeneratedKeys("id").mapTo(Integer.class).one());
    }
    void member(Jdbi db,String kind,String name,String descriptor,String flags) {
        db.useHandle(h->h.createUpdate("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES (1,:kind,:name,:signature,:descriptor,'int','[]',:flags,'[]')")
                .bind("kind",kind).bind("name",name).bind("signature",name+descriptor).bind("descriptor",descriptor).bind("flags",flags).execute());
    }
    JsonNode result() {return ApiCompatibilityQueries.compare(before,after,20);}
    List<String> reasons(JsonNode node) {List<String> values=new ArrayList<>();node.path("findings").forEach(f->values.add(f.path("reason").asText()));return values;}
    JsonNode finding(String reason) {for(JsonNode f:result().path("findings"))if(f.path("reason").asText().equals(reason))return f;throw new AssertionError(result().toString());}
    void kotlin(Jdbi db,String kind,String name,String jvmName,String descriptor,String parameters,
            KotlinDeclarationTypes types,boolean suspend,boolean extension,boolean mutable) {
        db.useHandle(h->{
            String memberKind = kind.equals("CONSTRUCTOR") ? "CONSTRUCTOR" : kind.equals("PROPERTY")
                    && !descriptor.startsWith("(") ? "FIELD" : "METHOD";
            h.createUpdate("""
                    INSERT OR IGNORE INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations)
                    VALUES(1,:kind,:name,:signature,:descriptor,'int','[]','public','[]')
                    """).bind("kind",memberKind).bind("name",jvmName).bind("signature",jvmName+descriptor)
                    .bind("descriptor",descriptor).execute();
            h.createUpdate("""
                INSERT INTO kotlin_declarations(class_id,kind,name,jvm_name,descriptor,semantic_model,
                    is_suspend,is_extension,is_mutable,has_default_parameters,value_parameters,declaration_types)
                VALUES(1,:kind,:name,:jvmName,:descriptor,'kotlin_metadata',:suspend,:extension,:mutable,
                    :defaults,:parameters,:types)
                """).bind("kind",kind).bind("name",name).bind("jvmName",jvmName).bind("descriptor",descriptor)
                .bind("suspend",suspend?1:0).bind("extension",extension?1:0).bind("mutable",mutable?1:0)
                .bind("defaults",parameters!=null && parameters.contains("\"hasDefault\":true")?1:0)
                .bind("parameters",parameters).bind("types",types==null?null:new ObjectMapper().valueToTree(types).toString()).execute();
        });
    }
    KotlinDeclarationTypes.Type type(boolean nullable) {
        return new KotlinDeclarationTypes.Type("class","kotlin/String",nullable,false,false,List.of(),null,null,null,null);
    }
    KotlinDeclarationTypes types(boolean resultNullable,boolean parameterNullable) {
        return new KotlinDeclarationTypes(type(resultNullable),null,
                List.of(new KotlinDeclarationTypes.Parameter("value",type(parameterNullable),null)),List.of(),List.of());
    }
    @Test void unchangedNeverClaimsCompatibilityAndPrivateMembersAreExcluded() {
        member(before,"METHOD","run","()V","private");
        assertEquals("no_detected_changes",result().path("verdict").asText());assertFalse(result().path("compatibility_proven").asBoolean());
    }
    @Test void kotlinDefaultRemovalAndNamedParameterRenameBreakSourceCallsWithStableJvmDescriptor() {
        member(before,"METHOD","run","(Ljava/lang/String;)Ljava/lang/String;","public");
        member(after,"METHOD","run","(Ljava/lang/String;)Ljava/lang/String;","public");
        kotlin(before,"FUNCTION","run","run","(Ljava/lang/String;)Ljava/lang/String;",
                "[{\"name\":\"value\",\"hasDefault\":true,\"vararg\":false}]",null,false,false,false);
        kotlin(after,"FUNCTION","run","run","(Ljava/lang/String;)Ljava/lang/String;",
                "[{\"name\":\"renamed\",\"hasDefault\":false,\"vararg\":false}]",null,false,false,false);
        assertTrue(reasons(result()).containsAll(List.of("KOTLIN_DEFAULT_REMOVED","KOTLIN_PARAMETERS_CHANGED")));
        assertEquals("potentially_breaking",result().path("verdict").asText());
        assertEquals("value",finding("KOTLIN_DEFAULT_REMOVED").path("before_kotlin").path("value_parameters").get(0).path("name").asText());
    }
    @Test void kotlinDefaultAdditionRequiresReviewAndMissingParameterEvidenceIsUnknown() {
        kotlin(before,"FUNCTION","run","run","(I)V",
                "[{\"name\":\"value\",\"hasDefault\":false,\"vararg\":false}]",null,false,false,false);
        kotlin(after,"FUNCTION","run","run","(I)V",
                "[{\"name\":\"value\",\"hasDefault\":true,\"vararg\":false}]",null,false,false,false);
        assertEquals("review_required",finding("KOTLIN_DEFAULT_ADDED").path("assessment").asText());
        after.useHandle(h->h.execute("UPDATE kotlin_declarations SET value_parameters=NULL"));
        assertEquals("unknown",finding("KOTLIN_PARAMETER_EVIDENCE_INCOMPLETE").path("assessment").asText());
    }
    @Test void kotlinNullableReturnAndTightenedInputArePotentiallyBreaking() {
        kotlin(before,"FUNCTION","run","run","(Ljava/lang/String;)Ljava/lang/String;",
                "[{\"name\":\"value\",\"hasDefault\":false,\"vararg\":false}]",types(false,true),false,false,false);
        kotlin(after,"FUNCTION","run","run","(Ljava/lang/String;)Ljava/lang/String;",
                "[{\"name\":\"value\",\"hasDefault\":false,\"vararg\":false}]",types(true,false),false,false,false);
        assertEquals("potentially_breaking",finding("KOTLIN_NULLABILITY_WEAKENED").path("assessment").asText());
        after.useHandle(h->h.createUpdate("UPDATE kotlin_declarations SET declaration_types=:types")
                .bind("types",new ObjectMapper().valueToTree(types(false,false)).toString()).execute());
        assertEquals("potentially_breaking",finding("KOTLIN_NULLABILITY_WEAKENED").path("assessment").asText());
        after.useHandle(h->h.createUpdate("UPDATE kotlin_declarations SET declaration_types=:types")
                .bind("types",new ObjectMapper().valueToTree(types(false,true)).toString()).execute());
        assertFalse(reasons(result()).contains("KOTLIN_NULLABILITY_WEAKENED"));
    }
    @Test void kotlinCallConventionAndPropertyMutabilityAreReportedOncePerProperty() {
        kotlin(before,"FUNCTION","run","run","(Ljava/lang/String;)V","[]",null,false,true,false);
        kotlin(after,"FUNCTION","run","run","(Ljava/lang/String;)V","[]",null,true,false,false);
        for(String accessor:List.of("getState","setState")) {
            kotlin(before,"PROPERTY","state",accessor,"()Ljava/lang/String;",null,null,false,false,true);
            kotlin(after,"PROPERTY","state",accessor,"()Ljava/lang/String;",null,null,false,false,false);
        }
        assertEquals("potentially_breaking",finding("KOTLIN_CALL_CONVENTION_CHANGED").path("assessment").asText());
        assertEquals(1,reasons(result()).stream().filter("KOTLIN_PROPERTY_BECAME_READ_ONLY"::equals).count());
    }
    @Test void kotlinSafeNullabilityDirectionRequestsReviewAndMissingMetadataDoesNotInventBreakage() {
        kotlin(before,"FUNCTION","run","run","(Ljava/lang/String;)Ljava/lang/String;","[]",types(true,false),false,false,false);
        kotlin(after,"FUNCTION","run","run","(Ljava/lang/String;)Ljava/lang/String;","[]",types(false,true),false,false,false);
        assertEquals("review_required",finding("KOTLIN_SOURCE_TYPES_CHANGED").path("assessment").asText());
        assertFalse(reasons(result()).contains("KOTLIN_NULLABILITY_WEAKENED"));
        after.useHandle(h->h.execute("DELETE FROM kotlin_declarations"));
        assertEquals("no_detected_changes",result().path("verdict").asText());
        assertFalse(result().path("compatibility_proven").asBoolean());
    }
    @Test void privateKotlinDeclarationDoesNotPollutePublicApiReview() {
        kotlin(before,"FUNCTION","secret","secret","()V","[]",null,false,false,false);
        kotlin(after,"FUNCTION","renamed","secret","()V","[]",null,false,false,false);
        before.useHandle(h->h.execute("UPDATE class_members SET modifiers='private'"));
        after.useHandle(h->h.execute("UPDATE class_members SET modifiers='private'"));
        assertEquals("no_detected_changes",result().path("verdict").asText());
    }
    @Test void kotlinSourceNameAndVarargChangesAreVisibleWithUnchangedJvmMembers() {
        kotlin(before,"FUNCTION","sourceName","jvmName","([Ljava/lang/String;)V",
                "[{\"name\":\"values\",\"hasDefault\":false,\"vararg\":true}]",null,false,false,false);
        kotlin(after,"FUNCTION","renamed","jvmName","([Ljava/lang/String;)V",
                "[{\"name\":\"values\",\"hasDefault\":false,\"vararg\":false}]",null,false,false,false);
        assertTrue(reasons(result()).containsAll(List.of("KOTLIN_NAME_CHANGED","KOTLIN_PARAMETERS_CHANGED")));
    }
    @Test void kotlinPropertyBecomingMutableRequestsReview() {
        kotlin(before,"PROPERTY","state","getState","()Ljava/lang/String;",null,null,false,false,false);
        kotlin(after,"PROPERTY","state","getState","()Ljava/lang/String;",null,null,false,false,true);
        assertEquals("review_required",finding("KOTLIN_PROPERTY_BECAME_MUTABLE").path("assessment").asText());
    }
    @Test void reportsPotentiallyBreakingModifierChanges() {
        String[][] cases={{"public","protected","VISIBILITY_NARROWED"},{"protected","private","VISIBILITY_NARROWED"},
                {"public","","VISIBILITY_NARROWED"},{"public static","public","STATIC_INSTANCE_CHANGED"},
                {"public","public static","STATIC_INSTANCE_CHANGED"},{"public","public final","FINAL_ADDED"},{"public","public abstract","ABSTRACT_ADDED"}};
        for(String[] item:cases) {
            before.useHandle(h->h.execute("DELETE FROM class_members"));after.useHandle(h->h.execute("DELETE FROM class_members"));
            member(before,"METHOD","run","()V",item[0]);member(after,"METHOD","run","()V",item[1]);
            assertEquals("potentially_breaking",finding(item[2]).path("assessment").asText(),Arrays.toString(item));
        }
    }
    @Test void descriptorChangesAreRemovalAndAdditionEvenIfHumanSignatureIsIdentical() {
        member(before,"METHOD","run","()Ljava/lang/Number;","public");member(after,"METHOD","run","()Ljava/lang/Integer;","public");
        after.useHandle(h->h.execute("UPDATE class_members SET signature='same'"));before.useHandle(h->h.execute("UPDATE class_members SET signature='same'"));
        assertTrue(reasons(result()).containsAll(List.of("MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED","MEMBER_ADDED")));
    }
    @Test void retainedOverloadsHaveIndependentIdentityAndImpactIsExact() {
        member(before,"METHOD","run","(I)V","public");member(before,"METHOD","run","(Ljava/lang/String;)V","public");
        member(after,"METHOD","run","(Ljava/lang/String;)V","public");
        int caller=owner(before,"p.Caller","main");
        before.useHandle(h->{for(String descriptor:List.of("(I)V","(Ljava/lang/String;)V"))h.createUpdate("INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind) VALUES (:caller,'invoke','()V',1,'run',:descriptor,'INVOKESTATIC')")
                .bind("caller",caller).bind("descriptor",descriptor).execute();});
        JsonNode impact=finding("MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED").path("baseline_impact");
        assertEquals(1,impact.path("consumer_count").asInt());assertEquals("p.Caller",impact.path("consumers").get(0).path("class_name").asText());
    }
    @Test void fieldAccessesAndConstructorsUseJvmDescriptorsAndInitName() {
        member(before,"FIELD","value","I","public");member(after,"FIELD","value","J","public");
        member(before,"CONSTRUCTOR","Api","(I)V","public");
        int caller=owner(before,"p.Caller","main");
        before.useHandle(h->{h.createUpdate("INSERT INTO field_accesses(from_class_id,from_method,from_descriptor,to_class_id,field_name,field_descriptor,access_kind) VALUES (:caller,'invoke','()V',1,'value','I','GETFIELD')").bind("caller",caller).execute();
            h.createUpdate("INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind) VALUES (:caller,'invoke','()V',1,'<init>','(I)V','INVOKESPECIAL')").bind("caller",caller).execute();});
        int checked=0;for(JsonNode f:result().path("findings"))if(f.path("reason").asText().equals("MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED")){assertEquals(1,f.path("baseline_impact").path("consumer_count").asInt());checked++;}
        assertEquals(2,checked);
    }
    @Test void abstractAdditionAndSubclassEvidenceAreReportedWithoutCallInstructions() {
        member(after,"METHOD","run","()V","public abstract");int child=owner(before,"p.Child","main");
        before.useHandle(h->h.createUpdate("UPDATE classes SET superclass='p.Api' WHERE id=:id").bind("id",child).execute());
        assertEquals(1,finding("ABSTRACT_METHOD_ADDED").path("baseline_impact").path("direct_subtype_count").asInt());
    }
    @Test void annotationAndGenericSurfaceChangesRequireReviewNotBinaryFailure() {
        member(before,"METHOD","run","()Ljava/util/List;","public");member(after,"METHOD","run","()Ljava/util/List;","public");
        after.useHandle(h->h.execute("UPDATE class_members SET type_name='java.util.List<java.lang.String>',annotations='[\"p.Nullable\"]'"));
        assertEquals("review_required",result().path("verdict").asText());assertTrue(reasons(result()).containsAll(List.of("SOURCE_SIGNATURE_CHANGED","ANNOTATIONS_CHANGED")));
    }
    @Test void orderOnlyModifiersAnnotationsAndInterfacesDoNotInventChanges() {
        member(before,"METHOD","run","()V","public static");member(after,"METHOD","run","()V","static public");
        before.useHandle(h->{h.execute("UPDATE classes SET interfaces='[\"p.B\",\"p.A\"]'");h.execute("UPDATE class_members SET annotations='[\"p.B\",\"p.A\"]'");});
        after.useHandle(h->{h.execute("UPDATE classes SET interfaces='[\"p.A\",\"p.B\"]'");h.execute("UPDATE class_members SET annotations='[\"p.A\",\"p.B\"]'");});
        assertEquals(0,result().path("finding_count").asInt());
    }
    @Test void malformedOrMissingDescriptorsRemainUnknown() {
        for(String descriptor:List.of("","garbage","(V)V","()","()[V","Ljava.lang.String;","(I)VI")) {
            before.useHandle(h->h.execute("DELETE FROM class_members"));after.useHandle(h->h.execute("DELETE FROM class_members"));
            member(before,"METHOD","run",descriptor,"public");member(after,"METHOD","run",descriptor,"public");
            assertEquals("unknown",result().path("verdict").asText(),descriptor);assertTrue(reasons(result()).contains("MEMBER_EVIDENCE_INCOMPLETE"));
        }
    }
    @Test void ambiguousOwnersDoNotProduceDefiniteMemberRemovals() {
        member(before,"METHOD","run","()V","public");owner(before,"p.Api","main");
        assertEquals("unknown",result().path("verdict").asText());assertEquals(List.of("AMBIGUOUS_OWNER"),reasons(result()));
    }
    @Test void testCustomAndOrphanClassesAreExcludedButLegacyEvidenceIsIncluded() {
        owner(before,"p.TestApi","test");owner(before,"p.CustomApi","integrationTest");int orphan=owner(before,"p.Orphan","main");
        before.useHandle(h->h.createUpdate("UPDATE classes SET origin='orphan_output' WHERE id=:id").bind("id",orphan).execute());
        assertEquals(0,result().path("finding_count").asInt());owner(before,"p.Legacy",null);
        assertEquals("CLASS_REMOVED",result().path("findings").get(0).path("reason").asText());
    }
    @Test void countsRemainCompleteWhenDetailsAndConsumersAreTruncatedAndOrderIsStable() {
        member(before,"METHOD","a","()V","public");member(before,"METHOD","b","()V","public");
        for(int i=0;i<3;i++){int caller=owner(before,"p.Caller"+i,"main");before.useHandle(h->h.createUpdate("INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind) VALUES (:caller,'invoke','()V',1,'a','()V','INVOKESTATIC')").bind("caller",caller).execute());}
        JsonNode report=ApiCompatibilityQueries.compare(before,after,1);assertTrue(report.path("truncated").asBoolean());assertTrue(report.path("finding_count").asInt()>1);
        JsonNode impact=report.path("findings").get(0).path("baseline_impact");assertEquals(3,impact.path("consumer_count").asInt());assertEquals(1,impact.path("consumers").size());assertTrue(impact.path("consumers_truncated").asBoolean());
        assertEquals(report,ApiCompatibilityQueries.compare(before,after,1));
    }
    @Test void classKindAndHierarchyChangesAreIndependentOfMemberChanges() {
        after.useHandle(h->h.execute("UPDATE classes SET kind='INTERFACE',superclass=NULL,interfaces='[\"p.Marker\"]'"));
        assertTrue(reasons(result()).containsAll(List.of("CLASS_KIND_CHANGED","HIERARCHY_CHANGED")));
    }
    @Test void addedAndRemovedOwnersDoNotShareImpactCacheWhenDatabaseIdsCoincide() {
        owner(before,"p.Consumer","main");
        before.useHandle(h->h.execute("INSERT INTO dependencies(from_class_id,to_class_id,kind,occurrence_count) VALUES (2,1,'METHOD_CALL',1)"));
        after.useHandle(h->h.execute("UPDATE classes SET class_name='p.New' WHERE id=1"));
        assertEquals(1,finding("CLASS_REMOVED").path("baseline_impact").path("consumer_count").asInt());
        assertEquals("unavailable",finding("CLASS_ADDED").path("baseline_impact").path("status").asText());
    }

    @Test void missingDescriptorsOnKnownPrivateMembersDoNotPollutePublicApiReview() {
        member(before,"METHOD","secret","","private");
        assertEquals(0,result().path("finding_count").asInt());
    }
    @Test void classAnnotationChangesAreReviewItemsAndUnknownModifierEncodingStaysUnknown() {
        before.useHandle(h->h.execute("INSERT INTO class_annotations(class_id,annotation_name,direct) VALUES (1,'p.Export',1)"));
        assertEquals("review_required",finding("CLASS_ANNOTATIONS_CHANGED").path("assessment").asText());
        member(before,"METHOD","run","()V","ACC_PUBLIC");
        assertEquals("unknown",finding("MEMBER_EVIDENCE_INCOMPLETE").path("assessment").asText());
    }
    @Test void publicProtectedAndPrivateVisibilityAreParsedAsTokensNotSubstrings() {
        member(before,"METHOD","run","()V","public private");
        assertEquals("unknown",result().path("verdict").asText());
    }

    @Test void annotationObjectKeyOrderIsIgnoredButArrayValueOrderIsPreserved() {
        member(before,"METHOD","run","()V","public");member(after,"METHOD","run","()V","public");
        before.useHandle(h->h.execute("UPDATE class_members SET annotation_details='[{\"name\":\"p.Annotation\",\"value\":[1,2]}]'"));
        after.useHandle(h->h.execute("UPDATE class_members SET annotation_details='[{\"value\":[1,2],\"name\":\"p.Annotation\"}]'"));
        assertEquals(0,result().path("finding_count").asInt());
        after.useHandle(h->h.execute("UPDATE class_members SET annotation_details='[{\"value\":[2,1],\"name\":\"p.Annotation\"}]'"));
        assertTrue(reasons(result()).contains("ANNOTATIONS_CHANGED"));
    }

}
