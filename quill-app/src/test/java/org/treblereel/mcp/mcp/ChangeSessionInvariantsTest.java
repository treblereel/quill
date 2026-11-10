package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * What agents read from a change_session answer, as they receive it, in every phase. Claude quotes
 * phase, verified and the receipt; Codex prints phase, phase_gate, directive, the receipt and the
 * review checklist and nothing else (V21-V28). Trimming an answer must keep all of these.
 */
class ChangeSessionInvariantsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;
    private Jdbi jdbi;

    @BeforeEach void index() throws Exception {
        jdbi = QuillDatabase.create(root.resolve(".quill/index.db"));
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO classes (class_name, kind, superclass, interfaces, source_file, source_line,
                                     is_bean, source_tokens, origin, lifecycle, module, source_set)
                VALUES ('org.acme.OrderService', 'CLASS', 'java.lang.Object', '[]',
                        'src/main/java/org/acme/OrderService.java', 1, 0, 50, 'source', 'current', '.', 'main')"""));
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>a</artifactId><version>1</version></project>");
    }

    @AfterEach void stop() { JavaSemanticQueries.close(); }

    private final ChangeSessionRepeats repeats = new ChangeSessionRepeats();

    /** As QuillTools answers: the snapshot, then repeat elision, then the catalog's compaction. */
    private JsonNode ask(String checkout) throws Exception {
        String raw = ReadQueryContext.readWithGraph(jdbi, context -> new ChangeSessionQueries().snapshot(context, root,
                List.of("OrderService"), "Add a method", 10, "summary", "auto", checkout));
        return JSON.readTree(ResponseCompaction.apply(repeats.elide("k", raw), "change_session", Map.of()));
    }

    private void askTwice(String checkout, String phase) throws Exception {
        assertReadable(ask(checkout), phase);
        assertReadable(ask(checkout), phase + " repeated");
    }

    private static void assertReadable(JsonNode answer, String phase) {
        String context = phase + ": " + answer;
        assertEquals(phase.replace(" repeated", ""), answer.path("phase").asText(), context);
        assertTrue(answer.path("phase_gate").path("status").isTextual(), context);
        JsonNode primary = answer.path("directive").path("primary_action");
        assertTrue(primary.path("action").isTextual(), "The directive names its action itself: " + context);
        assertFalse(primary.has("same_as"), "Codex reads only the directive: " + context);
        assertFalse(answer.path("next_actions").isEmpty(), context);
        if (answer.has("verification_receipt")) {
            assertTrue(answer.path("verification_receipt").path("verified").isBoolean(), context);
            assertTrue(answer.path("verification_receipt").path("receipt_id").isTextual(), context);
        }
        if (answer.has("verification_plan")) {
            assertTrue(answer.path("verification_plan").path("commands").valueStream()
                    .anyMatch(command -> command.path("scope").asText().equals("quick_compile")), context);
        }
    }

    @Test void everyPhaseKeepsWhatAgentsRead() throws Exception {
        Path source = root.resolve("src/main/java/org/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; class OrderService {}\n");
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern("src").addFilepattern("pom.xml").call();
            git.commit().setMessage("initial").setAuthor("T", "t@example.com").setSign(false).call();

            askTwice(null, "planned");

            Files.writeString(source, "package org.acme; class OrderService { void changed() {} }\n");
            askTwice(null, "review_required");

            Path state = root.resolve(".quill/build-state.json");
            Files.writeString(state, """
                    {"buildTool":"maven","finishedAt":%d,"successful":false,"captureScope":"task_output",
                     "diagnostics":["compile error"],"failureMessages":[]}
                    """.formatted(System.currentTimeMillis()));
            askTwice(null, "blocked");

            Path clone = Files.createTempDirectory("quill-invariant-clone");
            try {
                askTwice(clone.toString(), "external_checkout");
            } finally {
                Files.deleteIfExists(clone);
            }
        }
    }
}
