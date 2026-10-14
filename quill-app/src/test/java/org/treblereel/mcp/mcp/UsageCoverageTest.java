package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.WorktreeSnapshotCache;

class UsageCoverageTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path root; Jdbi db;
    @BeforeEach void fixture() throws Exception {
        db=JavaEvidenceFixture.index(root,"""
            package p;
            class Target { static void ping(){} static void lonely(){} static final int CONSTANT=42; }
            class Caller { void first(){Target.ping();} void second(){Target.ping();} int constant(){return Target.CONSTANT;} }
            """);
    }
    JsonNode method(String name,int limit,int offset) throws Exception {
        return JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,"p.Target",name,"method",null,"all",limit,offset));
    }
    void metadata(String key,String value) { db.useHandle(h -> h.execute("INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)",key,value)); }
    Set<String> reasons(JsonNode result) { return new HashSet<>(result.path("coverage").path("incomplete_reasons").valueStream().map(JsonNode::asText).toList()); }
    @Test void completeScopedZeroNeverProvesNonUseAndListsExactlyWhatWasIndexed() throws Exception {
        var result=method("lonely",10,0);
        assertEquals(0,result.path("total").asInt()); assertTrue(result.path("answer_complete").asBoolean(),result.toString());
        assertEquals("no_matching_references_in_indexed_scope",result.path("empty_result").path("interpretation").asText());
        assertFalse(result.path("unused_proven").asBoolean());
        var coverage=result.path("coverage");
        assertEquals("indexed_application_bytecode",coverage.path("scope").asText());
        assertEquals("current_observed_worktree",coverage.path("snapshot_freshness").asText());
        assertEquals("main",coverage.path("indexed_scopes").get(0).path("source_set").asText());
        assertEquals(2,coverage.path("indexed_scopes").get(0).path("class_count").asInt());
        assertFalse(coverage.path("external_consumers_covered").asBoolean());
        assertFalse(coverage.path("runtime_dispatch_exhaustive").asBoolean());
        var field=JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,"p.Target","CONSTANT","field",null,"read",10,0));
        assertEquals(0,field.path("total").asInt()); assertFalse(field.path("unused_proven").asBoolean());
        assertFalse(field.path("coverage").path("source_only_references_covered").asBoolean());
    }
    @Test void anEmptyPageWithNonzeroTotalCannotBePresentedAsZeroUsages() throws Exception {
        var first=method("ping",1,0);
        var outside=method("ping",1,999);
        assertEquals(2,first.path("total").asInt()); assertFalse(first.path("answer_complete").asBoolean());
        assertTrue(reasons(first).contains("page_window_incomplete"));
        assertEquals("empty_page_not_zero_usages",outside.path("empty_result").path("interpretation").asText());
        assertEquals(2,outside.path("empty_result").path("indexed_total").asInt());
        assertFalse(outside.path("unused_proven").asBoolean());
    }
    @Test void missingModuleOrTestOutputsAndMalformedLegacyMetadataMakeZeroUnknown() throws Exception {
        metadata("module_discovery_complete","false"); metadata("missing_test_output_modules","[\"child\"]");
        var partial=method("lonely",10,0);
        assertEquals("unknown_current_usage_coverage",partial.path("empty_result").path("interpretation").asText());
        assertTrue(reasons(partial).containsAll(Set.of("module_discovery_partial","test_outputs_missing")));
        metadata("module_discovery_complete","true"); metadata("missing_test_output_modules","{broken");
        assertTrue(reasons(method("lonely",10,0)).contains("test_output_coverage_unknown"));
        db.useHandle(h -> h.execute("DELETE FROM metadata WHERE key IN('index_id','project_root','module_discovery_complete')"));
        var legacy=method("lonely",10,0); assertFalse(legacy.path("answer_complete").asBoolean());
        assertTrue(reasons(legacy).containsAll(Set.of("freshness_evidence_unavailable","module_discovery_unknown")));
    }
    @Test void sourceChangesWithRestoredTimestampAndRebuiltOutputsInvalidateCurrentCoverage() throws Exception {
        Path source=root.resolve("src/main/java/p/Fixture.java");
        var time=Files.getLastModifiedTime(source); Files.writeString(source,Files.readString(source).replace("42","43")); Files.setLastModifiedTime(source,time);
        WorktreeSnapshotCache.shared().invalidate(root);
        var stale=method("lonely",10,0);
        assertEquals("stale",stale.path("coverage").path("snapshot_freshness").asText()); assertFalse(stale.path("answer_complete").asBoolean());
        Path classes=root.resolve("target/classes/p/Target.class");
        Files.setLastModifiedTime(classes,FileTime.fromMillis(System.currentTimeMillis()+2000));
        assertTrue(reasons(method("lonely",10,0)).contains("compiled_outputs_changed_after_index"));
    }
    @Test void missingCompiledOutputsAreNotAnEmptyCurrentProject() throws Exception {
        try(var files=Files.walk(root.resolve("target/classes"))) { for(Path file:files.filter(Files::isRegularFile).toList()) Files.delete(file); }
        var result=method("lonely",10,0); assertFalse(result.path("answer_complete").asBoolean());
        assertTrue(reasons(result).contains("compiled_output_coverage_unverified"));
        assertEquals("missing",result.path("coverage").path("compiled_outputs").path("state").asText());
    }
    @Test void unclassifiedMemberHandlesAndLegacyDynamicRowsPreventNegativeCertainty() throws Exception {
        db.useHandle(h -> {
            h.execute("INSERT INTO dependencies(from_class_id,to_class_id,kind,occurrence_count) SELECT id,id,'METHOD_HANDLE_REFERENCE',1 FROM classes WHERE class_name='p.Target'");
            h.execute("""
                INSERT INTO method_calls(from_class_id,from_method,from_descriptor,to_class_id,to_method,to_descriptor,invocation_kind,occurrence_count,evidence_lines)
                SELECT id,'create','()V',id,'other','()V','dynamic',1,'[1]' FROM classes WHERE class_name='p.Target'
                """);
        });
        var result=method("lonely",10,0);
        assertTrue(reasons(result).containsAll(Set.of("unclassified_member_handles","legacy_bootstrap_classification_unknown")));
        assertEquals("unknown_current_usage_coverage",result.path("empty_result").path("interpretation").asText());
    }
    @Test void classUsagesAndCallHierarchyExposeTheSameCoverageAndFilters() throws Exception {
        var classes=JSON.readTree(new UsageToolQueries().findUsages(db,"p.Target","annotation",null,10,0));
        assertTrue(classes.has("coverage")); assertEquals("annotation",classes.path("empty_result").path("filters").path("usage_kind").asText());
        var hierarchy=JSON.readTree(new CallHierarchyQueries().getCallHierarchy(db,"p.Target","lonely",null,"inbound",false,1,10,0));
        assertTrue(hierarchy.path("coverage").path("indexed_scope_verified").asBoolean());
        assertEquals("inbound",hierarchy.path("empty_result").path("filters").path("direction").asText());
        assertFalse(hierarchy.path("unused_proven").asBoolean());
    }
    @Test void scopeInventoryIncludesTestsAndKeepsLegacyLabelsUnknownWithoutIncludingOrphans() throws Exception {
        db.useHandle(h -> {
            h.execute("INSERT INTO classes(class_name,kind,source_file,source_line,is_bean,source_tokens,module,source_set) VALUES('p.Test','CLASS','test.java',1,0,1,'child','test')");
            h.execute("INSERT INTO classes(class_name,kind,source_file,source_line,is_bean,source_tokens,origin,module,source_set) VALUES('p.Orphan','CLASS','orphan.java',1,0,1,'orphan_output','ghost','main')");
        });
        var scopes=method("lonely",10,0).path("coverage").path("indexed_scopes");
        assertEquals(2,scopes.size()); assertTrue(scopes.valueStream().anyMatch(s -> s.path("source_set").asText().equals("test")));
        assertFalse(scopes.valueStream().anyMatch(s -> s.path("module").asText().equals("ghost")));
        db.useHandle(h -> h.execute("UPDATE classes SET source_set=NULL WHERE class_name='p.Test'"));
        assertTrue(reasons(method("lonely",10,0)).contains("unlabeled_indexed_scopes"));
    }
    @Test void ignoredSourceAdditionsAndDeletionsAreDetectedByContentEvidence() throws Exception {
        Path added=root.resolve("src/main/java/p/Added.java");
        Files.createDirectories(root.resolve(".git/info"));
        Files.writeString(root.resolve(".git/info/exclude"),"src/main/java/p/Added.java\n");
        Files.writeString(added,"package p; class Added {}\n");
        WorktreeSnapshotCache.shared().invalidate(root);
        assertEquals("changed",method("lonely",10,0).path("coverage").path("source_snapshot").path("state").asText());
        Files.delete(added); Files.delete(root.resolve("src/main/java/p/Fixture.java"));
        WorktreeSnapshotCache.shared().invalidate(root);
        assertEquals("stale",method("lonely",10,0).path("coverage").path("snapshot_freshness").asText());
    }
    @Test void malformedSourceSnapshotsAndOversizedScopeInventoriesStayIncomplete() throws Exception {
        metadata("java_source_hashes","null");
        assertTrue(reasons(method("lonely",10,0)).contains("indexed_java_source_snapshot_unverified"));
        db.useHandle(h -> {
            for(int i=0;i<101;i++) h.execute("INSERT INTO classes(class_name,kind,source_file,source_line,is_bean,source_tokens,module,source_set) VALUES(?,'CLASS','generated.java',1,0,1,?,'main')","p.Generated"+i,"module"+i);
        });
        var result=method("lonely",10,0);
        assertEquals(100,result.path("coverage").path("indexed_scopes").size());
        assertTrue(result.path("coverage").path("scope_inventory_truncated").asBoolean());
        assertTrue(reasons(result).contains("scope_inventory_truncated"));
    }
    @Test void unavailableGitFreshnessIsExplicitEvenWhenTheCapturedJavaSourcesMatch() throws Exception {
        try(var files=Files.walk(root.resolve(".git"))) {
            for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        WorktreeSnapshotCache.shared().invalidate(root);
        var result=method("lonely",10,0);
        assertEquals("matching",result.path("coverage").path("source_snapshot").path("state").asText());
        assertEquals("unknown",result.path("coverage").path("snapshot_freshness").asText());
        assertEquals("unknown_current_usage_coverage",result.path("empty_result").path("interpretation").asText());
    }
}
