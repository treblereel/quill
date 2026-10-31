package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryResponseTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void largeInventoriesKeepExactCountsAndLateFailuresWithoutChangingDecisions() throws Exception {
        var full = JSON.createObjectNode().put("phase", "blocked");
        full.putObject("phase_gate").put("status", "action_required");
        var build = full.putObject("verification").putObject("build");
        build.set("compiled_outputs", outputs());
        var scope = build.putObject("verification_scope").put("kind", "modules_with_prerequisites");
        var modules = scope.putArray("modules");
        var coverage = full.withObject("/verification").putObject("test_evidence").putObject("test_index_coverage");
        var indexed = coverage.putArray("indexed_modules");
        for (int i = 0; i < 1000; i++) { modules.add("module-" + i); indexed.add("module-" + i); }
        var receipt = full.putObject("verification_receipt").put("verified", false);
        receipt.putArray("reason_codes").add("BLOCKER_BUILD_EVIDENCE_REQUIRED");
        receipt.putObject("observed_evidence").set("compiled_outputs", outputs());
        var argv = full.putObject("verification_plan").putArray("commands").addObject().putArray("argv");
        argv.add("mvn").add("-pl").add(String.join(",", modules.valueStream().map(n -> n.asText()).toList())).add("test-compile");
        var summary = full.deepCopy();
        var omitted = ToolResponseSupport.summarizeInventories(summary);
        assertEquals(full.path("phase_gate"), summary.path("phase_gate"));
        assertEquals(full.path("verification_receipt").path("reason_codes"), summary.path("verification_receipt").path("reason_codes"));
        assertFalse(summary.path("verification_receipt").path("verified").asBoolean());
        assertEquals(full.path("verification_plan"), summary.path("verification_plan"));
        for (String pointer : List.of("/verification/build/compiled_outputs", "/verification_receipt/observed_evidence/compiled_outputs")) {
            var outputs = summary.at(pointer);
            assertEquals(1000, outputs.path("source_sets_count").asInt());
            assertEquals(5, outputs.path("source_sets").size());
            assertTrue(outputs.path("source_sets_has_more").asBoolean());
            assertEquals(1, outputs.path("source_sets_status_counts").path("changed").asInt());
            assertEquals(999, outputs.path("source_sets_status_counts").path("matching").asInt());
        }
        assertEquals(1000, summary.at("/verification/build/verification_scope/modules_count").asInt());
        assertEquals(1000, summary.at("/verification/test_evidence/test_index_coverage/indexed_modules_count").asInt());
        assertFalse(omitted.isEmpty());
        assertTrue(JSON.writeValueAsBytes(full).length > 300_000);
        assertTrue(JSON.writeValueAsBytes(summary).length < 20_000);
        String once = summary.toString();
        ToolResponseSupport.summarizeInventories(summary);
        assertEquals(once, summary.toString());
        assertEquals(1000, full.at("/verification/build/compiled_outputs/source_sets").size());
    }

    @Test void compactIssuesLinkToFullBuildEvidenceInsteadOfRepeatingPlansForEveryProject() {
        var plan = JSON.createObjectNode();
        plan.set("compiled_outputs", outputs());
        var issue = new ProjectRegistry.ProjectIssue("example", Path.of("/example"), "build_required", "maven",
                "Compile the stale module", "Run quick_compile from the detailed build status", false,
                "classes_stale", List.of("module-999")).buildEvidence(plan, plan);
        var full = ProjectAvailabilityResponses.details(issue);
        var compact = ProjectAvailabilityResponses.summary(issue);
        assertFalse(compact.has("verification_plan"));
        assertFalse(compact.has("diagnostics"));
        for (String field : List.of("status", "reason", "action", "previous_generation_available", "recommended_action"))
            assertEquals(full.path(field), compact.path(field));
        assertEquals("get_build_status", compact.path("details_request").path("tool").asText());
        assertEquals("example", compact.at("/details_request/arguments/project").asText());
        assertTrue(compact.toString().length() * 43 < 50_000);
        assertEquals(1000, full.at("/verification_plan/compiled_outputs/source_sets").size());
        var response = JSON.createObjectNode().put("detail", "summary");
        ProjectAvailabilityResponses.append(response, "project_warnings", List.of(issue));
        assertFalse(response.at("/project_warnings/0").has("verification_plan"));
    }

    @Test void sampledModulesCannotProveThatAWarningIsOutsideTheChange() {
        for (boolean largeScope : List.of(true, false)) {
            var response = JSON.createObjectNode();
            var scope = response.putObject("verification").putObject("build")
                    .putObject("verification_scope").put("kind", "modules_with_prerequisites");
            var modules = scope.putArray("modules");
            var warning = response.putArray("project_warnings").addObject().put("build_reason", "classes_stale");
            var stale = warning.putArray("stale_modules");
            modules.add("shared"); stale.add("shared");
            var many = largeScope ? modules : stale;
            many.removeAll();
            for (int i = 0; i < 10; i++) many.add("other-" + i);
            many.add("shared");
            ToolResponseSupport.summarizeInventories(response);
            ProjectAvailabilityResponses.annotateScopedBuildWarnings(response);
            assertFalse(warning.has("blocking_for_change"));
            assertNotEquals("repository_outside_change", warning.path("scope").asText());
        }
    }

    private static ObjectNode outputs() {
        var result = JSON.createObjectNode().put("status", "stale");
        var sets = result.putArray("source_sets"); var directories = result.putArray("directories");
        for (int i = 0; i < 1000; i++) {
            String directory = "/project/module-" + i + "/target/classes";
            directories.add(directory);
            sets.addObject().put("module", "module-" + i).put("source_set", "main")
                    .put("status", i == 999 ? "changed" : "matching").put("output", directory)
                    .put("compiled_at", "1792288908142").put("latest_class_modified_at", "2026-10-18T01:52:51.859Z")
                    .putArray("active_profiles").add("integration-tests");
        }
        return result;
    }
}
