package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.change.ChangeSessionQueries;
import org.treblereel.mcp.mcp.change.ChangeVerificationQueries;
import org.treblereel.mcp.mcp.change.ContextQueries;
import org.treblereel.mcp.mcp.project.WorktreeStatusQueries;
import org.treblereel.mcp.model.*;

/** Change planning, verification and change sessions. */
class QuillToolsChangeSessionTest extends QuillToolsTestBase {

    @Test
    void planChangeSeparatesPrimaryEditsFromDependencyReview() throws Exception {
        writeMinimalPom();

        JsonNode result =
                JSON.readTree(
                        JDBI_READS.planChange(
                                jdbi,
                                tempDir,
                                List.of("StripePaymentService"),
                                "Add idempotent payment retries",
                                10));

        assertEquals("Add idempotent payment retries", result.path("change").asText());
        assertEquals("partial", result.path("plan_status").asText());
        assertFalse(result.path("answer_complete").asBoolean());
        JsonNode primary = result.path("primary_changes").get(0);
        assertEquals("org.acme.StripePaymentService", primary.path("class").asText());
        assertEquals(
                "src/main/java/org/acme/StripePaymentService.java", primary.path("file").asText());
        JsonNode dependent = result.path("dependency_review").get(0);
        assertEquals("org.acme.OrderService", dependent.path("class").asText());
        assertEquals("injection", dependent.path("usage_kind").asText());
        assertEquals("review", dependent.path("action").asText());
        assertTrue(
                result.path("warnings")
                        .valueStream()
                        .anyMatch(
                                warning ->
                                        warning.path("code")
                                                .asText()
                                                .equals("INCOMPLETE_TEST_COVERAGE")));
        assertEquals(5, result.path("sequence").size());
        assertEquals("maven", result.path("verification_plan").path("build_system").asText());
        assertTrue(result.path("verification_plan").path("runner").has("available"));
        assertEquals("abc1234", result.path("_meta").path("indexed_commit").asText());
    }

    @Test
    void planChangeNamesTheOwnerOfAMethodTarget() throws Exception {
        writeMinimalPom();
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                  type_name, parameter_types, modifiers, annotations)
                VALUES (1, 'METHOD', 'work', 'work():void', '()V', 'void', '[]', 'public', '[]')"""));

        JsonNode result =
                JSON.readTree(
                        JDBI_READS.planChange(
                                jdbi, tempDir, List.of("OrderService#work()"), "Rename work", 10));

        JsonNode primary = result.path("primary_changes").get(0);
        assertEquals("org.acme.OrderService", primary.path("class").asText(), result.toString());
        assertEquals("src/main/java/org/acme/OrderService.java", primary.path("file").asText());
        assertEquals("work", primary.path("method").asText());
    }

    @Test
    void planChangeRequiresDescription() throws Exception {
        JsonNode result =
                JSON.readTree(
                        JDBI_READS.planChange(jdbi, tempDir, List.of("OrderService"), "  ", 10));

        assertEquals("A non-empty change description is required", result.path("message").asText());
    }

    @Test
    void planChangeProvidesExecutableContinuationForEveryAffectedTest() throws Exception {
        writeMinimalPom();
        addOrderTests(0, 27);
        JsonNode plan =
                JSON.readTree(
                        JDBI_READS.planChange(
                                jdbi,
                                tempDir,
                                List.of("OrderService"),
                                "Change order processing",
                                10));
        JsonNode testPlan = plan.path("test_plan");
        assertEquals(27, testPlan.path("total").asInt());
        assertEquals(10, testPlan.path("showing").asInt());
        assertTrue(testPlan.path("has_more").asBoolean());
        assertTrue(testPlan.path("answer_complete").asBoolean());
        JsonNode next = testPlan.path("next_page_request");
        assertEquals("find_impacted_tests", next.path("tool").asText());
        JsonNode args = next.path("arguments");
        assertEquals(10, args.path("offset").asInt());
        assertEquals(10, args.path("limit").asInt());
        assertEquals(3, args.path("max_depth").asInt());
        assertEquals("org.acme.OrderService", args.path("targets").get(0).asText());

        JsonNode second =
                JSON.readTree(
                        JDBI_READS.findImpactedTests(
                                jdbi, List.of("org.acme.OrderService"), true, 3, 10, 10));
        JsonNode third =
                JSON.readTree(
                        JDBI_READS.findImpactedTests(
                                jdbi, List.of("org.acme.OrderService"), true, 3, 10, 20));
        assertEquals(10, second.path("showing").asInt());
        assertEquals(7, third.path("showing").asInt());
        assertFalse(third.path("has_more").asBoolean());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode page : List.of(testPlan, second, third)) {
            for (JsonNode test : page.path("tests")) names.add(test.path("class").asText());
        }
        assertEquals(27, names.size());

        JsonNode session =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        readScope,
                                                        tempDir,
                                                        List.of("OrderService"),
                                                        "Change order processing",
                                                        10,
                                                        "summary",
                                                        "plan")));
        assertEquals(next, session.path("plan").path("test_plan").path("next_page_request"));
    }

    @Test
    void planChangeContinuationAppearsOnlyBeyondHundredTests() throws Exception {
        writeMinimalPom();
        addOrderTests(0, 100);
        JsonNode exact =
                JSON.readTree(
                        JDBI_READS.planChange(
                                jdbi,
                                tempDir,
                                List.of("OrderService"),
                                "Change order processing",
                                100));
        assertEquals(100, exact.path("test_plan").path("showing").asInt());
        assertFalse(exact.path("test_plan").path("has_more").asBoolean());
        assertFalse(exact.path("test_plan").has("next_page_request"));

        addOrderTests(100, 1);
        JsonNode overflow =
                JSON.readTree(
                        JDBI_READS.planChange(
                                jdbi,
                                tempDir,
                                List.of("OrderService"),
                                "Change order processing",
                                100));
        assertEquals(101, overflow.path("test_plan").path("total").asInt());
        assertEquals(100, overflow.path("test_plan").path("showing").asInt());
        assertEquals(
                100,
                overflow.path("test_plan")
                        .path("next_page_request")
                        .path("arguments")
                        .path("offset")
                        .asInt());
        JsonNode last =
                JSON.readTree(
                        JDBI_READS.findImpactedTests(
                                jdbi, List.of("org.acme.OrderService"), true, 3, 100, 100));
        assertEquals(1, last.path("showing").asInt());
        assertFalse(last.path("has_more").asBoolean());
    }

    @Test
    void verifyChangeBlocksOnCapturedBuildFailure() throws Exception {
        writeMinimalPom();
        Path state = tempDir.resolve(".quill/build-state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(
                state,
                """
                {
                  "buildTool": "maven",
                  "finishedAt": 1791067200000,
                  "successful": false,
                  "captureScope": "task_output",
                  "diagnostics": [
                    "src/main/java/org/acme/OrderService.java:10:5: incompatible types"
                  ],
                  "failureMessages": []
                }
                """);

        JsonNode result =
                JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of("OrderService"), 10));

        assertEquals("blocked", result.path("verdict").asText());
        assertFalse(result.path("verified").asBoolean());
        assertEquals("failed", result.path("diagnostics").path("build_status").asText());
        assertEquals("build_failed", result.path("build").path("reason").asText());
        assertEquals("fix_build", result.path("build").path("action").asText());
        assertEquals("last_build_failed", result.path("build").path("build_reason").asText());
        assertTrue(
                result.path("build").path("recommended_action").asText().contains("quick_compile"));
        assertEquals(1, result.path("diagnostics").path("total").asInt());
        assertEquals("BUILD_FAILED", result.path("blockers").get(0).path("code").asText());
        assertEquals("fix_diagnostics", result.path("next_actions").get(0).path("action").asText());
        assertEquals(
                "run_external_command", result.path("next_actions").get(1).path("action").asText());
        assertEquals(
                "quick_compile", result.path("next_actions").get(1).path("command_scope").asText());
        assertFalse(result.path("next_actions").get(1).path("executed_by_quill").asBoolean());
        assertEquals("verify_change", findAction(result, "call_tool").path("tool").asText());
        assertFalse(result.path("build").path("build_was_started").asBoolean());
        assertEquals("abc1234", result.path("_meta").path("indexed_commit").asText());
    }

    /**
     * V23 CQ-049: an offline dependency resolution failure came back as BUILD_FAILED with
     * fix_diagnostics.
     */
    @Test
    void verifyChangeNamesAnEnvironmentFailureApartFromCodeErrors() throws Exception {
        writeMinimalPom();
        Path state = tempDir.resolve(".quill/build-state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(
                state,
                """
                {"buildTool":"maven","finishedAt":1791067200000,"successful":false,"diagnostics":[],
                 "failureMessages":["Failed to execute goal on project app: Could not resolve dependencies for project io.acme:app:jar:1\\ndependency: io.acme:core:jar:1 (compile)\\n\\tCannot access central in offline mode and the artifact io.acme:core:jar:1 has not been downloaded from it before."]}
                """);

        JsonNode result =
                JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of("OrderService"), 10));

        assertEquals("blocked", result.path("verdict").asText());
        assertEquals(
                "BUILD_ENVIRONMENT",
                result.path("blockers").get(0).path("code").asText(),
                result.toString());
        JsonNode first = result.path("next_actions").get(0);
        assertEquals("fix_build_environment", first.path("action").asText(), result.toString());
        assertTrue(
                first.path("description").asText().contains("not the sources"), first.toString());
    }

    /**
     * V23 CQ-049: archunit failed test discovery and Quill said to fix compiler diagnostics with
     * test-compile.
     */
    @Test
    void verifyChangeNamesFailedTestsApartFromCompileErrors() throws Exception {
        writeMinimalPom();
        Path state = tempDir.resolve(".quill/build-state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(
                state,
                """
                {"buildTool":"maven","finishedAt":1791067200000,"successful":false,"diagnostics":[],
                 "failureMessages":["Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.6.1:test (default-test) on project app: There are test failures."]}
                """);

        JsonNode result =
                JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of("OrderService"), 10));

        assertEquals("blocked", result.path("verdict").asText());
        assertEquals(
                "TESTS_FAILED",
                result.path("blockers").get(0).path("code").asText(),
                result.toString());
        JsonNode first = result.path("next_actions").get(0);
        assertEquals("fix_failing_tests", first.path("action").asText(), result.toString());
        assertTrue(first.path("description").asText().contains("compiled"), first.toString());
        assertEquals(
                "module_fallback",
                result.path("next_actions").get(1).path("command_scope").asText(),
                "No tests are ranked here, so the retry is the module's test run, not a recompile: "
                        + result);
    }

    /**
     * V24 CQ-049: two verify_change answers were 28 and 21 KB, 10 KB of each the full rows of the
     * same 20 tests.
     */
    @Test
    void verifyChangeStatesAffectedTestsByWhatRanksThem() throws Exception {
        writeMinimalPom();
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderServiceTest', 'CLASS', 'java.lang.Object', '[]',
                            'src/test/java/org/acme/OrderServiceTest.java', 1, 0, 80,
                            'source', 'current', '.', 'test')""");
                    handle.execute(
                            """
                    INSERT INTO dependencies (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    SELECT t.id, s.id, 'CALLS', 1, '[7]' FROM classes t, classes s
                    WHERE t.class_name = 'org.acme.OrderServiceTest' AND s.class_name = 'org.acme.OrderService'""");
                });
        JsonNode result =
                JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of("OrderService"), 10));

        JsonNode row = result.path("test_evidence").path("tests").path(0);
        assertEquals("org.acme.OrderServiceTest", row.path("class").asText(), result.toString());
        assertTrue(row.has("score"), row.toString());
        for (String restated :
                List.of("reasons", "dependency_path", "evidence", "file", "dependency_kinds")) {
            assertFalse(row.has(restated), restated + " restates the rank: " + row);
        }
        assertFalse(
                result.path("verification_plan").has("focused_tests"),
                "The focused command already names them: " + result.path("verification_plan"));
    }

    @Test
    void verifyChangeRequiresBuildEvidenceWhenNoBuildWasCaptured() throws Exception {
        writeMinimalPom();
        JsonNode result =
                JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of("OrderService"), 10));

        assertEquals("needs_build", result.path("verdict").asText());
        assertEquals("unknown", result.path("diagnostics").path("build_status").asText());
        assertTrue(
                result.path("blockers")
                        .valueStream()
                        .anyMatch(
                                blocker ->
                                        blocker.path("code")
                                                .asText()
                                                .equals("BUILD_EVIDENCE_REQUIRED")));
        assertEquals(
                "run_external_command", result.path("next_actions").get(0).path("action").asText());
        assertEquals(
                "quick_compile", result.path("next_actions").get(0).path("command_scope").asText());
        assertEquals("verify_change", findAction(result, "call_tool").path("tool").asText());
    }

    @Test
    void verifyChangeInfersTargetsFromDirtyJvmSources() throws Exception {
        writeMinimalPom();
        Path source = tempDir.resolve("src/main/java/org/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; class OrderService {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            Files.writeString(
                    source, "package org.acme; class OrderService { void changed() {} }\n");

            JsonNode result = JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of(), 10));

            assertEquals("dirty_worktree", result.path("target_source").asText());
            assertEquals(1, result.path("inferred_target_count").asInt());
            assertFalse(result.path("target_inference_truncated").asBoolean());
            assertEquals(
                    "src/main/java/org/acme/OrderService.java",
                    result.path("requested_targets").get(0).asText());
            assertEquals(1, result.path("resolved_target_count").asInt());
        }
    }

    @Test
    void workflowInfersJvmTargetsBeyondPublicStatusPage() throws Exception {
        writeMinimalPom();
        Path source = tempDir.resolve("src/main/java/org/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; class OrderService {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            Path notes = Files.createDirectories(tempDir.resolve("a-evidence"));
            for (int i = 0; i < 150; i++) {
                Files.writeString(notes.resolve("note-%03d.md".formatted(i)), "unrelated");
            }
            Files.writeString(
                    source, "package org.acme; class OrderService { void changed() {} }\n");
            JsonNode page =
                    JSON.readTree(
                            ReadQueryContext.<String>readWithGraph(
                                    jdbi,
                                    readScope ->
                                            new WorktreeStatusQueries()
                                                    .getFreshWorktreeStatus(
                                                            readScope, tempDir, null, 10, 0)));
            assertEquals(10, page.path("showing").asInt());
            assertTrue(page.path("has_more").asBoolean());
            JsonNode verified =
                    JSON.readTree(JDBI_READS.verifyChange(jdbi, tempDir, List.of(), 10));
            JsonNode session =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi, tempDir, List.of(), "Change method", 10));
            assertEquals(1, verified.path("inferred_target_count").asInt());
            assertFalse(verified.path("worktree").path("has_more").asBoolean());
            assertEquals(
                    "src/main/java/org/acme/OrderService.java",
                    verified.path("requested_targets").get(0).asText());
            assertEquals(verified.path("requested_targets"), session.path("requested_targets"));
            assertFalse(session.path("verification").path("worktree").path("has_more").asBoolean());
            ObjectNode context =
                    (ObjectNode)
                            JSON.readTree(
                                    ReadQueryContext.<String>readWithGraph(
                                            jdbi,
                                            readScope ->
                                                    new ContextQueries()
                                                            .getContext(
                                                                    readScope,
                                                                    List.of("OrderService"),
                                                                    false,
                                                                    10)));
            JsonNode withContext =
                    JSON.readTree(
                            ReadQueryContext.<String>readWithGraph(
                                    jdbi,
                                    readScope ->
                                            new ChangeVerificationQueries()
                                                    .verifyChangeWithContext(
                                                            readScope,
                                                            tempDir,
                                                            List.of("OrderService"),
                                                            10,
                                                            context)));
            assertFalse(withContext.path("worktree").path("has_more").asBoolean());
            for (int i = 0; i < 10; i++) {
                Files.writeString(
                        source.resolveSibling("Unknown%d.java".formatted(i)),
                        "package org.acme; class Unknown%d {}".formatted(i));
            }
            JsonNode tooMany =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi, tempDir, List.of(), "Change methods", 10));
            assertTrue(tooMany.path("target_inference_truncated").asBoolean());
            assertFalse(tooMany.path("verification_receipt").path("verified").asBoolean());
        }
    }

    @Test
    void changeSessionSeesAnEditImmediatelyAfterAPlannedSnapshot() throws Exception {
        writeMinimalPom();
        Path source = tempDir.resolve("src/main/java/org/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; class OrderService {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            JsonNode planned =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi, tempDir, List.of("OrderService"), "Add a method", 10));

            Files.writeString(
                    source, "package org.acme; class OrderService { void changed() {} }\n");
            JsonNode changed =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi, tempDir, List.of("OrderService"), "Add a method", 10));

            assertEquals("planned", planned.path("phase").asText());
            assertEquals("ready", planned.path("phase_gate").path("status").asText());
            assertTrue(
                    planned.path("phase_gate").path("can_proceed_without_resolution").asBoolean());
            assertEquals("review_required", changed.path("phase").asText());
            assertEquals("action_required", changed.path("phase_gate").path("status").asText());
            assertFalse(
                    changed.path("phase_gate").path("can_proceed_without_resolution").asBoolean());
            assertEquals(
                    "test_coverage",
                    changed.path("phase_gate")
                            .path("required_evidence")
                            .get(0)
                            .path("id")
                            .asText());
            assertTrue(
                    changed.path("verification")
                            .path("worktree")
                            .path("structural_dirty")
                            .asBoolean());
            assertEquals(
                    "INCOMPLETE_TEST_COVERAGE",
                    changed.path("directive").path("primary_action").path("reason").asText());

            Path state = tempDir.resolve(".quill/build-state.json");
            Files.createDirectories(state.getParent());
            Files.writeString(
                    state,
                    """
                    {"buildTool":"maven","finishedAt":%d,"successful":false,
                     "captureScope":"task_output","diagnostics":["compile error"],
                     "failureMessages":[]}
                    """
                            .formatted(System.currentTimeMillis()));
            JsonNode blocked =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi, tempDir, List.of("OrderService"), "Add a method", 10));
            assertEquals("blocked", blocked.path("phase").asText());
            assertEquals(
                    "fix_diagnostics_and_rebuild",
                    blocked.path("phase_gate").path("transition").asText());
            assertEquals(
                    "BUILD_FAILED",
                    blocked.path("phase_gate")
                            .path("required_evidence")
                            .get(0)
                            .path("reason_code")
                            .asText());
            assertFalse(blocked.path("verification_receipt").path("verified").asBoolean());
            assertTrue(
                    blocked.path("verification_receipt")
                            .path("reason_codes")
                            .valueStream()
                            .anyMatch(reason -> reason.asText().equals("FAILED_BUILD_EVENT")));
        }
    }

    @Test
    void changeSessionForAnotherCheckoutSaysQuillDoesNotCoverItAndPointsItsCommandsThere()
            throws Exception {
        writeMinimalPom();
        Path clone = Files.createTempDirectory("quill-clone");
        try {
            JsonNode external =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi,
                                    tempDir,
                                    List.of("OrderService"),
                                    "Rename a method",
                                    10,
                                    clone.toString()));
            JsonNode missing =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi,
                                    tempDir,
                                    List.of("OrderService"),
                                    "Rename a method",
                                    10,
                                    clone.resolve("absent").toString()));
            JsonNode here =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi,
                                    tempDir,
                                    List.of("OrderService"),
                                    "Rename a method",
                                    10,
                                    tempDir.toString()));

            assertEquals("external_checkout", external.path("phase").asText());
            assertEquals("not_applicable", external.path("phase_gate").path("status").asText());
            assertTrue(
                    external.path("phase_gate").path("can_proceed_without_resolution").asBoolean());
            JsonNode primary = external.path("directive").path("primary_action");
            assertEquals("verify_in_checkout", primary.path("action").asText());
            String path = clone.toRealPath().toString();
            assertEquals(path, primary.path("working_directory").asText());
            assertEquals(path, external.path("external_checkout").path("path").asText());
            assertFalse(external.path("external_checkout").path("covered_by_quill").asBoolean());
            assertFalse(external.has("verification"));
            assertFalse(external.path("verification_receipt").path("verified").asBoolean());
            assertEquals(
                    "not_covered", external.path("verification_receipt").path("verdict").asText());
            assertFalse(
                    external.path("external_checkout").path("commands").isEmpty(),
                    external.toString());
            for (JsonNode command : external.path("external_checkout").path("commands")) {
                assertFalse(command.toString().contains(tempDir.toString()), command.toString());
            }
            assertEquals(
                    "org.acme.OrderService",
                    external.path("plan").path("primary_changes").get(0).path("class").asText());

            assertTrue(missing.has("error_code") || missing.has("error"), missing.toString());
            assertEquals("planned", here.path("phase").asText());
        } finally {
            Files.deleteIfExists(clone);
        }
    }

    /** V23 CQ-049: a planned full answer carried 3 KB of test commands for edits not yet made. */
    @Test
    void aPlannedChangeSessionStatesOnlyTheCompileCommandEvenInFullDetail() throws Exception {
        writeMinimalPom();
        JsonNode planned =
                JSON.readTree(
                        JDBI_READS.changeSession(
                                jdbi, tempDir, List.of("OrderService"), "Rename a method", 10));

        assertEquals("planned", planned.path("phase").asText());
        assertFalse(
                planned.path("verification_plan").path("commands").isEmpty(), planned.toString());
        for (JsonNode command : planned.path("verification_plan").path("commands")) {
            assertEquals("quick_compile", command.path("scope").asText(), command.toString());
        }
        assertFalse(planned.path("verification_plan").has("focused_tests"));
        assertTrue(
                planned.path("omitted_sections")
                        .toString()
                        .contains("verification_plan.non_quick_compile_commands"));
    }

    @Test
    void aPlannedChangeSessionSaysHowToReportEditsInAnotherCheckout() throws Exception {
        writeMinimalPom();
        JsonNode planned =
                JSON.readTree(
                        JDBI_READS.changeSession(
                                jdbi, tempDir, List.of("OrderService"), "Rename a method", 10));

        assertTrue(planned.path("directive").path("other_checkout").asText().contains("checkout"));
    }

    @Test
    void changeSessionReturnsStableStatelessWorkflowSnapshot() throws Exception {
        writeMinimalPom();

        JsonNode first =
                JSON.readTree(
                        JDBI_READS.changeSession(
                                jdbi, tempDir, List.of("OrderService"), "Add retry support", 10));
        JsonNode second =
                JSON.readTree(
                        JDBI_READS.changeSession(
                                jdbi,
                                tempDir,
                                List.of("org.acme.OrderService"),
                                "Add retry support",
                                10));

        assertEquals(1, first.path("schema_version").asInt());
        assertEquals("stateless_snapshot", first.path("state_model").asText());
        assertEquals("full", first.path("detail").asText());
        assertEquals("explicit", first.path("target_source").asText());
        assertEquals("planned", first.path("phase").asText());
        assertEquals(
                "apply_structural_change", first.path("phase_gate").path("transition").asText());
        assertEquals(first.path("session_id").asText(), second.path("session_id").asText());
        assertEquals(
                first.path("next_actions").get(0).path("action_id").asText(),
                second.path("next_actions").get(0).path("action_id").asText());
        assertEquals("org.acme.OrderService", first.path("targets").get(0).asText());
        assertEquals(
                "org.acme.OrderService",
                first.path("plan").path("primary_changes").get(0).path("class").asText());
        assertEquals("needs_build", first.path("verification").path("verdict").asText());
        assertTrue(first.path("verification_plan").path("commands").isArray());
        assertEquals("required", first.path("review_checklist").path("status").asText());
        assertEquals(
                "evidence_only",
                first.path("review_checklist").path("acknowledgement_model").asText());
        assertEquals(
                first.path("session_id").asText(),
                first.path("verification_receipt").path("session_id").asText());
        assertEquals(
                "not_captured",
                first.path("verification_receipt")
                        .path("recommendation")
                        .path("command_attestation")
                        .asText());
        assertFalse(first.path("verification_receipt").path("verified").asBoolean());
        assertTrue(first.path("directive").path("primary_action").path("action").isTextual());
        assertEquals(
                "/directive/primary_action",
                first.path("next_actions").get(0).path("same_as").asText(),
                "The first action is stated once, in the directive Codex prints");
        assertEquals("planned", first.path("directive").path("status").asText());
        assertEquals(
                first.path("next_actions").size(),
                first.path("next_actions")
                        .valueStream()
                        .map(action -> action.path("action_id").asText())
                        .distinct()
                        .count());
    }

    @Test
    void changeSessionSummaryOmitsHeavyEvidenceAndKeepsQuickCompile() throws Exception {
        writeMinimalPom();

        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        readScope,
                                                        tempDir,
                                                        List.of("OrderService"),
                                                        "Add retry support",
                                                        10,
                                                        "summary")));

        assertEquals("summary", result.path("detail").asText());
        assertFalse(result.path("plan").path("primary_changes").get(0).has("member_contracts"));
        assertFalse(result.path("verification").path("worktree").has("changes"));
        assertTrue(
                result.path("verification_plan")
                        .path("commands")
                        .valueStream()
                        .allMatch(
                                command -> command.path("scope").asText().equals("quick_compile")));
        assertTrue(result.path("verification_plan").path("other_command_scopes").isArray());
        assertTrue(result.path("omitted_sections").isArray());
        assertEquals("change_session", result.path("full_evidence_request").path("tool").asText());
        assertEquals("full", result.at("/full_evidence_request/arguments/detail").asText());
        assertEquals(
                "OrderService", result.at("/full_evidence_request/arguments/targets/0").asText());
        JsonNode full =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        readScope,
                                                        tempDir,
                                                        List.of("OrderService"),
                                                        "Add retry support",
                                                        10,
                                                        "full")));
        assertEquals(full.path("phase"), result.path("phase"));
        assertEquals(full.path("phase_gate"), result.path("phase_gate"));
        assertEquals(full.path("directive"), result.path("directive"));
        assertEquals(
                full.path("verification_receipt").path("verified"),
                result.path("verification_receipt").path("verified"));
        assertEquals(
                full.path("verification_receipt").path("reason_codes"),
                result.path("verification_receipt").path("reason_codes"));
        assertFalse(full.has("full_evidence_request"));
        for (JsonNode snapshot : List.of(result, full)) {
            JsonNode attested =
                    snapshot.at("/verification_receipt/observed_evidence/compiled_outputs");
            assertEquals(
                    snapshot.at("/verification/build/compiled_outputs/status"),
                    attested.path("status"));
            assertEquals(
                    snapshot.at("/verification/build/compiled_outputs/count"),
                    attested.path("count"));
            assertFalse(attested.has("source_sets"), "verification.build already lists them");
            assertFalse(attested.has("directories"), "verification.build already lists them");
        }
    }

    @Test
    void changeSessionAutoViewFocusesOnPlanBeforeStructuralEdits() throws Exception {
        writeMinimalPom();

        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        readScope,
                                                        tempDir,
                                                        List.of("OrderService"),
                                                        "Add retry support",
                                                        10,
                                                        "summary",
                                                        "auto")));

        assertEquals("planned", result.path("phase").asText());
        assertEquals("plan", result.path("view").asText());
        assertTrue(result.has("plan"));
        assertTrue(result.has("phase_gate"));
        assertFalse(result.has("verification"));
        assertFalse(result.has("verification_plan"));
        assertFalse(result.has("verification_receipt"));
        assertFalse(result.has("review_checklist"));
        assertTrue(
                result.path("omitted_sections")
                        .valueStream()
                        .anyMatch(item -> item.asText().equals("verification_receipt")));
        assertEquals(
                "inspect_primary",
                result.path("directive").path("primary_action").path("action").asText());
        assertFalse(
                result.path("directive")
                        .path("primary_action")
                        .path("action_id")
                        .asText()
                        .isBlank());
        assertEquals(
                "inspect_primary",
                result.path("directive").path("primary_action").path("action").asText());
    }

    @Test
    void changeSessionViewsPreservePhaseDirectiveAndExposePrimaryEvidence() throws Exception {
        writeMinimalPom();
        Path source = tempDir.resolve("src/main/java/org/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; class OrderService {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            for (boolean dirty : List.of(false, true)) {
                if (dirty)
                    Files.writeString(
                            source, "package org.acme; class OrderService { void changed() {} }\n");
                JsonNode baseline = null;
                for (String view : List.of("auto", "plan", "verification", "all")) {
                    JsonNode result =
                            JSON.readTree(
                                    ReadQueryContext.<String>readWithGraph(
                                            jdbi,
                                            readScope ->
                                                    new ChangeSessionQueries()
                                                            .snapshot(
                                                                    readScope,
                                                                    tempDir,
                                                                    List.of("OrderService"),
                                                                    "Add retry support",
                                                                    10,
                                                                    "summary",
                                                                    view)));
                    if (baseline == null) baseline = result;
                    assertEquals(baseline.path("directive"), result.path("directive"));
                    assertEquals(baseline.path("phase_gate"), result.path("phase_gate"));
                    assertTrue(
                            result.path("directive")
                                    .path("primary_action")
                                    .has("evidence_snapshot"));
                    assertEquals(
                            dirty ? "inspect_test_evidence" : "inspect_primary",
                            result.path("directive")
                                    .path("primary_action")
                                    .path("action")
                                    .asText());
                }
            }
        }
    }

    @Test
    void changeSessionInfersTargetsFromDirtyJvmSources() throws Exception {
        writeMinimalPom();
        Path source = tempDir.resolve("src/main/java/org/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; class OrderService {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            Files.writeString(
                    source, "package org.acme; class OrderService { void changed() {} }\n");

            JsonNode result =
                    JSON.readTree(
                            JDBI_READS.changeSession(
                                    jdbi, tempDir, List.of(), "Add retry support", 10));

            assertEquals("dirty_worktree", result.path("target_source").asText());
            assertEquals(
                    "src/main/java/org/acme/OrderService.java",
                    result.path("requested_targets").get(0).asText());
            assertEquals("org.acme.OrderService", result.path("targets").get(0).asText());
            assertEquals("review_required", result.path("phase").asText());

            JsonNode auto =
                    JSON.readTree(
                            ReadQueryContext.<String>readWithGraph(
                                    jdbi,
                                    readScope ->
                                            new ChangeSessionQueries()
                                                    .snapshot(
                                                            readScope,
                                                            tempDir,
                                                            List.of(),
                                                            "Add retry support",
                                                            10,
                                                            "summary",
                                                            "auto")));
            assertEquals("verification", auto.path("view").asText());
            assertFalse(auto.has("plan"));
            assertTrue(auto.has("verification"));
            assertTrue(auto.has("verification_plan"));
            assertTrue(auto.has("review_checklist"));
            assertTrue(auto.has("verification_receipt"));
        }
    }

    @Test
    void changeSessionUsesVerificationPhaseWhenOnlyBuildEvidenceIsMissing() {
        ObjectNode verification = JSON.createObjectNode();
        verification.put("verdict", "needs_build");
        verification.putObject("worktree").put("structural_dirty", true);
        verification.putArray("blockers").addObject().put("code", "BUILD_EVIDENCE_REQUIRED");

        assertEquals("verification_required", ChangeSessionQueries.phase(verification));
        ObjectNode checklist = JSON.createObjectNode();
        checklist.putArray("items");
        JsonNode gate = ChangeSessionQueries.phaseGate("verification_required", checklist);
        assertEquals("capture_successful_build", gate.path("transition").asText());
        assertEquals(
                "BUILD_EVIDENCE_REQUIRED",
                gate.path("required_evidence").get(0).path("reason_code").asText());
        assertFalse(gate.path("can_proceed_without_resolution").asBoolean());
    }

    @Test
    void changeSessionSharesClassInventoryAcrossSixTargets() throws Exception {
        var indexed =
                JavaEvidenceFixture.index(
                        tempDir,
                        """
                package p;
                class One {} class Two {} class Three {}
                class Four {} class Five {} class Six {}
                """);
        AtomicInteger inventories = new AtomicInteger();
        indexed.setSqlLogger(
                new SqlLogger() {
                    @Override
                    public void logAfterExecution(StatementContext context) {
                        if (context.getRawSql().startsWith("SELECT * FROM classes WHERE lifecycle"))
                            inventories.incrementAndGet();
                    }
                });
        var targets = List.of("p.One", "p.Two", "p.Three", "p.Four", "p.Five", "p.Six");
        String snapshot =
                ReadQueryContext.read(
                        indexed,
                        context ->
                                new ChangeSessionQueries()
                                        .snapshot(
                                                context,
                                                tempDir,
                                                targets,
                                                "Update persistence",
                                                40,
                                                "summary",
                                                "verification"));
        JsonNode result = JSON.readTree(snapshot);
        assertEquals(6, result.path("targets").size());
        assertTrue(result.has("phase_gate"));
        assertTrue(result.has("verification_receipt"));
        assertEquals(1, inventories.get());
        indexed.useHandle(
                handle ->
                        handle.execute(
                                "UPDATE classes SET lifecycle='historical' WHERE class_name='p.Six'"));
        assertEquals(
                5,
                ReadQueryContext.read(indexed, context -> context.symbols.findAllClasses()).size());
    }
}
