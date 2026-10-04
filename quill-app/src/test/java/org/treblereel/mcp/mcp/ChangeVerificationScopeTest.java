package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.command.BuildStatusInspector;
import org.treblereel.mcp.db.QuillDatabase;

class ChangeVerificationScopeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void unrelatedStaleModuleRemainsVisibleButDoesNotBlockTargetVerification() throws Exception {
        fixture();
        Jdbi db = database();
        JsonNode repository = JSON.readTree(BuildStatusInspector.inspect(root, db));
        assertEquals("build_required", repository.path("status").asText());
        JsonNode result = verify(db);
        assertEquals("ready", result.path("verdict").asText());
        assertTrue(result.path("verified").asBoolean());
        assertEquals("fresh", result.path("build").path("compiled_outputs").path("status").asText());
        assertTrue(result.path("build").path("repository_stale_modules").toString().contains("unrelated"));
        assertEquals(List.of(".", "app", "base"), result.path("verification_plan")
                .path("verification_scope").path("modules").valueStream().map(JsonNode::asText).toList());
    }

    @Test
    void staleProvidedPrerequisiteStillBlocks() throws Exception {
        fixture();
        stale("base/src/main/java/Thing.java");
        JsonNode result = verify(database());
        assertEquals("needs_build", result.path("verdict").asText());
        assertTrue(result.path("build").path("compiled_outputs").path("stale_modules").toString().contains("base"));
    }

    @Test
    void testPrerequisiteAlsoBelongsToCompileScope() throws Exception {
        fixture();
        Path pom = root.resolve("app/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("provided", "test"));
        stale("base/src/main/java/Thing.java");
        assertEquals("needs_build", verify(database()).path("verdict").asText());
    }

    @Test
    void missingPrerequisiteOutputsStillBlock() throws Exception {
        fixture();
        Files.delete(root.resolve("base/target/classes/Thing.class"));
        assertEquals("needs_build", verify(database()).path("verdict").asText());
    }

    @Test
    void sharedParentBuildInputStillInvalidatesSelectedModules() throws Exception {
        fixture();
        stale("pom.xml");
        assertEquals("needs_build", verify(database()).path("verdict").asText());
    }

    @Test
    void staleTargetStillBlocks() throws Exception {
        fixture();
        stale("app/src/main/java/Thing.java");
        assertEquals("needs_build", verify(database()).path("verdict").asText());
    }

    @Test
    void unknownDependencyScopeFallsBackToMatchingWholeRepositoryCommand() throws Exception {
        fixture();
        Path pom = root.resolve("app/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<artifactId>base</artifactId>",
                "<artifactId>${selected.dependency}</artifactId>"));
        JsonNode result = verify(database());
        assertEquals("repository", result.path("verification_plan").path("verification_scope").path("kind").asText());
        assertEquals("needs_build", result.path("verdict").asText());
        assertFalse(result.path("verification_plan").path("commands").get(0).path("argv").toString().contains("-pl"));
    }

    @Test
    void capturedFailureIsNotHiddenByModuleScoping() throws Exception {
        fixture();
        Files.writeString(root.resolve(".quill/build-state.json"),
                "{\"buildTool\":\"maven\",\"successful\":false,\"diagnostics\":[],\"failureMessages\":[]}");
        assertEquals("blocked", verify(database()).path("verdict").asText());
    }

    private void stale(String path) throws Exception {
        Files.setLastModifiedTime(root.resolve(path), FileTime.fromMillis(System.currentTimeMillis() + 10000));
    }

    @Test
    void buildPluginPrerequisiteAlsoBlocksWhenStale() throws Exception {
        fixture();
        Path pom = root.resolve("app/pom.xml");
        String text = Files.readString(pom).replaceAll("<dependencies>.*?</dependencies>",
                "<build><plugins><plugin><groupId>example</groupId><artifactId>base</artifactId>"
                        + "<version>1</version></plugin></plugins></build>");
        Files.writeString(pom, text);
        stale("base/src/main/java/Thing.java");
        assertEquals("needs_build", verify(database()).path("verdict").asText());
    }

    @Test
    void externalParentFallsBackRatherThanAssumingInheritedDependenciesAreKnown() throws Exception {
        fixture();
        Path pom = root.resolve("app/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<artifactId>root</artifactId>",
                "<artifactId>external-parent</artifactId>"));
        JsonNode result = verify(database());
        assertEquals("repository", result.path("verification_plan").path("verification_scope").path("kind").asText());
        assertEquals("needs_build", result.path("verdict").asText());
    }

    private Jdbi database() {
        Jdbi db = QuillDatabase.create(root.resolve("test.db"));
        db.useHandle(h -> {
            h.execute("INSERT INTO metadata(key,value) VALUES ('indexed_at','2099-01-01T00:00:00Z')");
            h.execute("INSERT INTO metadata(key,value) VALUES ('last_commit','unknown')");
        });
        return db;
    }

    @Test
    void onlyProvenOutsideScopeClassWarningsBecomeAdvisory() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree("""
                {"verification":{"build":{"verification_scope":{"kind":"modules_with_prerequisites",
                  "modules":[".","app","base"]}}},"project_warnings":[
                  {"build_reason":"classes_stale","stale_modules":["unrelated"]},
                  {"build_reason":"classes_stale","stale_modules":["base","unrelated"]},
                  {"build_reason":"classes_missing","stale_modules":["unrelated"]}]}
                """);
        QuillTools.annotateScopedBuildWarnings(response);
        assertEquals(false, response.path("project_warnings").get(0).path("blocking_for_change").asBoolean(true));
        assertEquals("repository_outside_change", response.path("project_warnings").get(0).path("scope").asText());
        assertFalse(response.path("project_warnings").get(1).has("blocking_for_change"));
        assertFalse(response.path("project_warnings").get(2).has("blocking_for_change"));
        ((ObjectNode) response.path("verification").path("build").path("verification_scope")).put("kind", "repository");
        QuillTools.annotateScopedBuildWarnings(response);
        assertFalse(response.path("project_warnings").get(1).has("blocking_for_change"));
    }

    private JsonNode verify(Jdbi db) throws Exception {
        ObjectNode context = (ObjectNode) JSON.readTree("""
                {"answer_complete":true,"requested_targets":["Thing"],"resolved_target_count":1,
                 "unresolved_target_count":0,"contexts":[{"resolution":{"module":"app"}}],
                 "impacted_tests":{"answer_complete":true,"tests":[]},
                 "_meta":{"structure_stale":false}}
                """);
        return JSON.readTree(new ChangeVerificationQueries().verifyChangeWithContext(db, root,
                List.of("Thing"), 20, context, JSON.createObjectNode(), false, false));
    }

    private void fixture() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>root</artifactId>
                <version>1</version><packaging>pom</packaging><modules><module>base</module><module>app</module>
                <module>unrelated</module></modules></project>
                """);
        long now = System.currentTimeMillis();
        for (String module : List.of("base", "app", "unrelated")) {
            Path dir = Files.createDirectories(root.resolve(module));
            String dependency = module.equals("app") ? "<dependencies><dependency><groupId>example</groupId>"
                    + "<artifactId>base</artifactId><version>1</version><scope>provided</scope></dependency></dependencies>" : "";
            Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                    + "<parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent>"
                    + "<artifactId>" + module + "</artifactId>" + dependency + "</project>");
            Path source = dir.resolve("src/main/java/Thing.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class Thing {}\n");
            Files.setLastModifiedTime(source, FileTime.fromMillis(now - 1000));
            Path classes = dir.resolve("target/classes/Thing.class");
            Files.createDirectories(classes.getParent());
            Files.write(classes, new byte[] {0, 1});
            Files.setLastModifiedTime(classes, FileTime.fromMillis(now + 1000));
        }
        Files.setLastModifiedTime(root.resolve("unrelated/src/main/java/Thing.java"), FileTime.fromMillis(now + 2000));
        Files.createDirectories(root.resolve(".quill"));
        Files.writeString(root.resolve(".quill/build-state.json"),
                "{\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":" + (now + 1000)
                        + ",\"diagnostics\":[],\"failureMessages\":[]}");
    }
}
