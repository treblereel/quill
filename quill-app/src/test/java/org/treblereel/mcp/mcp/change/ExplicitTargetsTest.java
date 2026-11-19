package org.treblereel.mcp.mcp.change;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.ClassRecord;

class ExplicitTargetsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void explicitTargetsSurviveEveryWorkflowStageBeyondBothFormerCaps() throws Exception {
        var db = fixture();
        var targets = targets();
        ReadQueryContext.readWithGraph(
                db,
                reader -> {
                    try {
                        var context = new ContextQueries().getContext(reader, targets, false, 40);
                        assertEquals(24, context.path("resolved_target_count").asInt());
                        assertEquals(24, context.path("impacted_tests").path("targets").size());
                        var session =
                                new ChangeSessionQueries()
                                        .snapshot(
                                                reader,
                                                root,
                                                targets,
                                                "Update all targets",
                                                40,
                                                "summary",
                                                "all");
                        assertEquals(JSON.valueToTree(targets), session.path("requested_targets"));
                        assertEquals(24, session.path("targets").size());
                        assertEquals(24, session.path("plan").path("primary_changes").size());
                        assertEquals(
                                JSON.valueToTree(targets),
                                session.at("/full_evidence_request/arguments/targets"));
                        assertFalse(session.path("target_inference_truncated").asBoolean());
                        var verification =
                                new ChangeVerificationQueries()
                                        .verifyChange(reader, root, targets, 40);
                        assertEquals(
                                JSON.valueToTree(targets), verification.path("requested_targets"));
                        assertEquals(24, verification.path("resolved_target_count").asInt());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    return null;
                });
    }

    @Test
    void unresolvedTailTargetBlocksVerificationInsteadOfBeingDropped() throws Exception {
        var db = fixture();
        var targets = new ArrayList<>(targets());
        targets.set(23, "acme.MissingTail");
        var session =
                ReadQueryContext.readWithGraph(
                        db,
                        reader ->
                                new ChangeSessionQueries()
                                        .snapshot(
                                                reader,
                                                root,
                                                targets,
                                                "Update all targets",
                                                40,
                                                "full",
                                                "all"));
        assertEquals(24, session.path("requested_targets").size());
        assertTrue(session.at("/verification/blockers").toString().contains("UNRESOLVED_TARGETS"));
        assertTrue(session.toString().contains("acme.MissingTail"));
        assertFalse(session.path("verification_receipt").path("verified").asBoolean());
        var verification =
                ReadQueryContext.readWithGraph(
                        db,
                        reader ->
                                new ChangeVerificationQueries()
                                        .verifyChange(reader, root, targets, 40));
        assertEquals(24, verification.path("requested_targets").size());
        assertEquals(1, verification.path("unresolved_target_count").asInt());
        assertFalse(verification.path("verified").asBoolean());
    }

    @Test
    void automaticInferenceKeepsItsDocumentedLimitAndReportsTruncation() throws Exception {
        var db = fixture();
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setSign(false)
                    .setAuthor("Fixture", "fixture@example.test")
                    .setMessage("fixture")
                    .call();
            for (int i = 1; i <= 12; i++)
                Files.writeString(
                        root.resolve("src/main/java/acme/Target" + i + ".java"),
                        "package acme; class Target" + i + " { int changed; }");
            var result =
                    ReadQueryContext.readWithGraph(
                            db,
                            reader ->
                                    new ChangeSessionQueries()
                                            .snapshot(
                                                    reader,
                                                    root,
                                                    null,
                                                    "Update dirty sources",
                                                    40));
            assertEquals(10, result.path("requested_targets").size());
            assertTrue(result.path("target_inference_truncated").asBoolean());
            assertTrue(
                    result.at("/verification/blockers")
                            .toString()
                            .contains("TARGET_INFERENCE_TRUNCATED"));
        }
    }

    private List<String> targets() {
        return IntStream.rangeClosed(1, 24).mapToObj(i -> "acme.Target" + i).toList();
    }

    private org.jdbi.v3.core.Jdbi fixture() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                """
                <project><modelVersion>4.0.0</modelVersion><groupId>acme</groupId>
                <artifactId>targets</artifactId><version>1</version></project>
                """);
        Files.writeString(root.resolve(".gitignore"), ".quill/\ntarget/\n");
        Files.createDirectories(root.resolve("src/main/java/acme"));
        var classes = new ArrayList<ClassRecord>();
        for (int i = 1; i <= 24; i++) {
            String source = "src/main/java/acme/Target" + i + ".java";
            Files.writeString(root.resolve(source), "package acme; class Target" + i + " {}");
            classes.add(
                    new ClassRecord(
                            i,
                            "acme.Target" + i,
                            "CLASS",
                            null,
                            List.of(),
                            source,
                            1,
                            false,
                            0,
                            null,
                            "source",
                            "current",
                            ".",
                            "main"));
        }
        var db = QuillDatabase.createForBulkLoad(root.resolve(".quill/index.db"));
        IndexWriter.writeFresh(
                db,
                new IndexFixture()
                        .classes(classes)
                        .metadata(Map.of("project_root", root.toString()))
                        .snapshot());
        return db;
    }
}
