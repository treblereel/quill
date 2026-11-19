package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.model.*;

/**
 * Apollo AQ-050 (Codex): an ambiguous UserInfoHolder listed seven candidates, three from test
 * sources and none with its @Profile, so Codex read AuthConfiguration whole (31 KB).
 */
class InjectionCandidateContextTest {
    @TempDir Path root;

    private static ClassRecord type(String name, String sourceSet) {
        return new ClassRecord(
                0,
                name,
                "CLASS",
                "java.lang.Object",
                List.of(),
                name + ".java",
                1,
                true,
                10,
                null,
                "source",
                "current",
                "m",
                sourceSet);
    }

    private Jdbi index(String consumerSourceSet) {
        return index(consumerSourceSet, List.of());
    }

    private Jdbi index(String consumerSourceSet, List<ClassAnnotationRecord> annotations) {
        Jdbi db = QuillDatabase.create(root.resolve(consumerSourceSet + ".db"));
        var beans =
                List.of(
                        new BeanRecord(
                                0,
                                1,
                                "CLASS",
                                "singleton",
                                List.of(),
                                List.of(),
                                false,
                                null,
                                null,
                                null,
                                null,
                                List.of("x.Consumer")),
                        new BeanRecord(
                                0,
                                2,
                                "CLASS",
                                "singleton",
                                List.of(),
                                List.of(),
                                false,
                                null,
                                List.of("ldap"),
                                null,
                                null,
                                List.of("x.Holder")),
                        new BeanRecord(
                                0,
                                3,
                                "CLASS",
                                "singleton",
                                List.of(),
                                List.of(),
                                false,
                                null,
                                null,
                                null,
                                null,
                                List.of("x.Holder")));
        var trace =
                new ResolutionTrace(
                        List.of(
                                new ResolutionCandidate(
                                        2,
                                        "x.LdapHolder",
                                        CandidateDisposition.ELIGIBLE,
                                        ResolutionReason.MULTIPLE_ELIGIBLE_CANDIDATES,
                                        List.of(ResolutionRule.TYPE_ASSIGNABILITY)),
                                new ResolutionCandidate(
                                        3,
                                        "x.TestHolder",
                                        CandidateDisposition.ELIGIBLE,
                                        ResolutionReason.MULTIPLE_ELIGIBLE_CANDIDATES,
                                        List.of(ResolutionRule.TYPE_ASSIGNABILITY))),
                        List.of(ResolutionRule.TYPE_ASSIGNABILITY),
                        List.of());
        var point =
                InjectionPointRecord.staticAnalysis(
                                0,
                                1,
                                "FIELD",
                                "x.Holder",
                                List.of(),
                                "holder",
                                null,
                                true,
                                InjectionPointRecord.STATIC_CDI)
                        .withResolution(null, true, trace);
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        type("x.Consumer", consumerSourceSet),
                                        type("x.LdapHolder", "main"),
                                        type("x.TestHolder", "test")))
                        .beans(beans)
                        .injectionPoints(List.of(point))
                        .classAnnotations(annotations)
                        .snapshot());
        return db;
    }

    private JsonNode trace(Jdbi db) throws Exception {
        return TestReads.read(
                        db,
                        scope -> new StructureToolQueries().getInjectionPoints(scope, "x.Consumer"))
                .path("injection_points")
                .get(0)
                .path("resolution_trace");
    }

    @Test
    void aCandidateStatesItsProfiles() throws Exception {
        JsonNode candidates = trace(index("main")).path("candidates");
        assertEquals(
                "x.LdapHolder", candidates.get(0).path("class").asText(), candidates.toString());
        assertEquals("[\"ldap\"]", candidates.get(0).path("profiles").toString());
    }

    @Test
    void aProductionInjectionPointFoldsTestSourceCandidates() throws Exception {
        JsonNode trace = trace(index("main"));
        assertEquals(1, trace.path("candidates").size(), trace.toString());
        assertEquals("[\"x.TestHolder\"]", trace.path("test_source_candidates").toString());
        assertTrue(
                trace.path("test_source_note").asText().contains("production"), trace.toString());
    }

    @Test
    void aTestInjectionPointKeepsTestSourceCandidates() throws Exception {
        JsonNode trace = trace(index("test"));
        assertEquals(2, trace.path("candidates").size(), trace.toString());
        assertFalse(trace.has("test_source_candidates"), trace.toString());
    }

    /** Apollo DefaultAuthAutoConfiguration: no @Profile, but @ConditionalOnMissingProfile. */
    @Test
    void aCandidateStatesItsClassConditionsOtherThanProfile() throws Exception {
        var conditional =
                new ClassAnnotationRecord(
                        2,
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                        true,
                        null,
                        java.util.Map.of());
        JsonNode candidates = trace(index("main", List.of(conditional))).path("candidates");
        assertTrue(
                candidates
                        .get(0)
                        .path("conditions")
                        .toString()
                        .contains("@ConditionalOnMissingBean"),
                candidates.toString());
    }
}
