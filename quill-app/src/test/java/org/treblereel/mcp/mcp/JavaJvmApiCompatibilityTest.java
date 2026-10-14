package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** API findings checked against javac and existing binary consumers. */
class JavaJvmApiCompatibilityTest {
    @TempDir Path root;
    ApiCompatibilityBytecodeTest helper() {var h=new ApiCompatibilityBytecodeTest();h.root=root;return h;}
    JsonNode compare(String before,String after) throws Exception {
        var h=helper();Path old=root.resolve("old"),now=root.resolve("now");
        assertTrue(h.compile(old,Map.of("p/Api.java",before),null));assertTrue(h.compile(now,Map.of("p/Api.java",after),null));
        return ApiCompatibilityQueries.compare(h.index(old,"old-db"),h.index(now,"now-db"),100);
    }
    JsonNode finding(JsonNode result,String reason) {
        return result.path("findings").valueStream().filter(f->f.path("reason").asText().equals(reason)).findFirst().orElseThrow(()->new AssertionError(result));
    }
    @Test void classFinalAbstractAndVisibilityHaveSeparateFindings() throws Exception {
        var report=compare("package p; public class Api {}","package p; abstract class Api {}");
        assertEquals("potentially_breaking",finding(report,"CLASS_VISIBILITY_NARROWED").path("assessment").asText());
        finding(report,"CLASS_ABSTRACT_ADDED");
        var h=helper();Path old=root.resolve("final-old"),now=root.resolve("final-now");
        assertTrue(h.compile(old,Map.of("p/Api.java","package p; public class Api {}"),null));
        assertTrue(h.compile(now,Map.of("p/Api.java","package p; public final class Api {}"),null));
        finding(ApiCompatibilityQueries.compare(h.index(old,"final-old-db"),h.index(now,"final-now-db"),100),"CLASS_FINAL_ADDED");
    }
    @Test void finalAndAbstractClassFindingsMatchExistingBinaryFailures() throws Exception {
        var h=helper();Path old=root.resolve("old"),finalApi=root.resolve("final"),abstractApi=root.resolve("abstract"),sub=root.resolve("sub"),client=root.resolve("client");
        assertTrue(h.compile(old,Map.of("p/Api.java","package p; public class Api {}"),null));
        assertTrue(h.compile(sub,Map.of("p/Use.java","package p; public class Use extends Api {public static void main(String[] a){System.out.println(new Use());}}"),old));
        assertTrue(h.compile(client,Map.of("p/Use.java","package p; public class Use {public static void main(String[] a){System.out.println(new Api());}}"),old));
        assertTrue(h.compile(finalApi,Map.of("p/Api.java","package p; public final class Api {}"),null));
        assertTrue(h.run(finalApi,sub,1).contains("IncompatibleClassChangeError"));
        finding(ApiCompatibilityQueries.compare(h.index(old,"final-before"),h.index(finalApi,"final-after"),100),"CLASS_FINAL_ADDED");
        assertTrue(h.compile(abstractApi,Map.of("p/Api.java","package p; public abstract class Api {}"),null));
        assertTrue(h.run(abstractApi,client,1).contains("InstantiationError"));
        finding(ApiCompatibilityQueries.compare(h.index(old,"abstract-before"),h.index(abstractApi,"abstract-after"),100),"CLASS_ABSTRACT_ADDED");
    }
    @Test void genericContractsChangeWithoutChangingJvmDescriptors() throws Exception {
        var report=compare("package p; public class Api<T> { public java.util.List<String> values(){return null;} }",
                "package p; public class Api<T extends Number> { public java.util.List<Integer> values(){return null;} }");
        finding(report,"CLASS_GENERIC_SIGNATURE_CHANGED");
        var generic=finding(report,"GENERIC_SIGNATURE_CHANGED");
        assertEquals("jvm_descriptor_unchanged",generic.path("binary_effect").asText());
        assertEquals("review_required",generic.path("assessment").asText());
        assertFalse(helper().has(report,"MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED"));
        assertTrue(generic.path("before").path("jvm_api").path("generic_signature").asText().contains("String"));
    }
    @Test void addedThrowsBreaksSourceRecompileButExistingBinaryStillRuns() throws Exception {
        var h=helper();Path old=root.resolve("old"),now=root.resolve("now"),client=root.resolve("client");
        String caller="package p; public class Use {public static void main(String[] a){System.out.println(Api.run());}}";
        assertTrue(h.compile(old,Map.of("p/Api.java","package p; public class Api {public static int run(){return 1;}}"),null));
        assertTrue(h.compile(client,Map.of("p/Use.java",caller),old));
        assertTrue(h.compile(now,Map.of("p/Api.java","package p; public class Api {public static int run() throws java.io.IOException {return 1;}}"),null));
        assertEquals("1",h.run(now,client,0).strip());
        assertFalse(h.compile(root.resolve("recompiled"),Map.of("p/Use.java",caller),now));
        var report=ApiCompatibilityQueries.compare(h.index(old,"old-db"),h.index(now,"now-db"),100);
        finding(report,"THROWS_CHANGED");assertEquals("review_required",report.path("verdict").asText());
    }
    @Test void changedConstantsWarnAboutAlreadyInlinedValues() throws Exception {
        var h=helper();Path old=root.resolve("old"),now=root.resolve("now"),client=root.resolve("client");
        assertTrue(h.compile(old,Map.of("p/Api.java","package p; public class Api {public static final int VALUE=1;}"),null));
        assertTrue(h.compile(client,Map.of("p/Use.java","package p; public class Use {public static void main(String[] a){System.out.println(Api.VALUE);}}"),old));
        assertTrue(h.compile(now,Map.of("p/Api.java","package p; public class Api {public static final int VALUE=2;}"),null));
        assertEquals("1",h.run(now,client,0).strip());
        var change=finding(ApiCompatibilityQueries.compare(h.index(old,"old-db"),h.index(now,"now-db"),100),"CONSTANT_VALUE_CHANGED");
        assertTrue(change.path("consumer_recompile_required").asBoolean());
        assertFalse(change.path("inlined_consumer_values_updated").asBoolean());
        assertEquals(0,change.path("baseline_impact").path("consumer_count").asInt());
    }
    @Test void exceptionOrderIsIgnoredAndFloatingSignedZeroIsRetained() throws Exception {
        var report=compare("package p; public class Api {public static final double ZERO=-0.0; public void run() throws java.io.IOException,java.sql.SQLException {}}",
                "package p; public class Api {public static final double ZERO=0.0; public void run() throws java.sql.SQLException,java.io.IOException {}}");
        finding(report,"CONSTANT_VALUE_CHANGED");assertFalse(helper().has(report,"THROWS_CHANGED"));
    }
    @Test void missingOrMalformedCaptureStaysUnknown() throws Exception {
        var h=helper();Path output=root.resolve("out");
        assertTrue(h.compile(output,Map.of("p/Api.java","package p; public class Api {}"),null));
        Jdbi before=h.index(output,"before"),after=h.index(output,"after");
        before.useHandle(handle->handle.execute("UPDATE jvm_api SET evidence='{\"schema_version\":1,\"status\":\"captured\"}'"));
        var report=ApiCompatibilityQueries.compare(before,after,100);
        finding(report,"JVM_API_EVIDENCE_INCOMPLETE");
        assertEquals(1,report.path("jvm_api_coverage").path("baseline_missing_classes").asInt());
        assertEquals("unknown",report.path("verdict").asText());
    }
    @Test void constantOnlyChangesAreAlsoVisibleInStructuralComparison() throws Exception {
        var h=helper();Path old=root.resolve("old"),now=root.resolve("now");
        assertTrue(h.compile(old,Map.of("p/Api.java","package p; public class Api {public static final int VALUE=1;}"),null));
        assertTrue(h.compile(now,Map.of("p/Api.java","package p; public class Api {public static final int VALUE=2;}"),null));
        Jdbi before=h.index(old,"baseline"),after=h.index(now,"current");
        before.useHandle(handle->{handle.execute("INSERT INTO metadata(key,value) VALUES ('index_id','baseline')");handle.execute("INSERT INTO metadata(key,value) VALUES ('indexed_at','2026-10-13T00:00:00Z')");});
        after.useHandle(handle->handle.execute("INSERT INTO metadata(key,value) VALUES ('index_id','current')"));
        Files.createDirectories(root.resolve(".quill"));Files.copy(root.resolve("baseline.db"),root.resolve(".quill/baseline.db"));
        JsonNode result=new ObjectMapper().readTree(new IndexComparisonQueries().compareIndex(after,root,"baseline",100));
        assertEquals(1,result.path("classes").path("modified_count").asInt(),result.toString());
        finding(result.path("api_compatibility"),"CONSTANT_VALUE_CHANGED");
    }
    @Test void inheritedSymbolicOwnersAreIncludedInBaselineImpact() throws Exception {
        Jdbi before=JavaEvidenceFixture.index(root.resolve("before"),"package p; class Base {public void run(){}} class Child extends Base {} class Use {void use(Child c){c.run();}}");
        Jdbi after=JavaEvidenceFixture.index(root.resolve("after"),"package p; class Base {} class Child extends Base {} class Use {void use(Child c){}}");
        var change=finding(ApiCompatibilityQueries.compare(before,after,100),"MEMBER_REMOVED_OR_DESCRIPTOR_CHANGED");
        assertEquals(1,change.path("baseline_impact").path("consumer_count").asInt(),change.toString());
        assertEquals("p.Use",change.path("baseline_impact").path("consumers").get(0).path("class_name").asText());
    }
}
