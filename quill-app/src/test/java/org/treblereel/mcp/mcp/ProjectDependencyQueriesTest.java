package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.diagnostics.DebugTrace;

class ProjectDependencyQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    @Test
    void reportsMavenAndGradleArtifactsByConsumingModuleWithoutBuilding() throws Exception {
        Path mavenJar = temp.resolve("home/.m2/repository/com/acme/widget/1.2/widget-1.2-tests.jar");
        Path gradleJar = temp.resolve(
                "home/.gradle/caches/modules-2/files-2.1/org.demo/lib/3.0/hash/lib-3.0.jar");
        Files.createDirectories(mavenJar.getParent());
        Files.createDirectories(gradleJar.getParent());
        Files.write(mavenJar, new byte[] {1});
        Files.write(gradleJar, new byte[] {1});
        Path first = temp.resolve("api/target/quill-classpath.txt");
        Path second = temp.resolve("worker/target/quill-classpath.txt");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, mavenJar.toString());
        Files.writeString(second, gradleJar.toString());
        Files.writeString(temp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <modules><module>api</module><module>worker</module></modules>
                </project>
                """);
        Files.writeString(temp.resolve("api/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>api</artifactId><version>1</version>
                  <dependencies><dependency><groupId>com.acme</groupId>
                    <artifactId>widget</artifactId><version>1.2</version>
                  </dependency></dependencies>
                </project>
                """);
        Files.writeString(temp.resolve("worker/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>worker</artifactId><version>1</version>
                </project>
                """);
        Jdbi jdbi = database();
        module(jdbi, "api");
        module(jdbi, "worker");

        var result = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, null,
                        null, null, null, null, 100, 0));

        assertEquals(2, result.path("total").asInt());
        assertFalse(result.path("discovery").path("build_invoked").asBoolean());
        assertEquals("com.acme:widget:1.2:tests",
                result.path("dependencies").get(0).path("id").asText());
        assertEquals("api",
                result.path("dependencies").get(0).path("used_by_modules").get(0).asText());
        assertEquals(true, result.path("dependencies").get(0).path("direct").asBoolean());
        assertEquals("compile", result.path("dependencies").get(0)
                .path("declared_scopes").get(0).asText());
        assertEquals("api",
                result.path("dependencies").get(0).path("direct_in_modules").get(0).asText());
        assertEquals("org.demo:lib:3.0",
                result.path("dependencies").get(1).path("id").asText());
        assertFalse(result.path("dependencies").get(1).path("direct").asBoolean());
        assertEquals("worker",
                result.path("dependencies").get(1).path("transitive_in_modules").get(0).asText());
    }

    @Test
    void filtersAndPaginatesArtifacts() throws Exception {
        Path repository = temp.resolve(".m2/repository");
        Path alpha = repository.resolve("a/alpha/1/alpha-1.jar");
        Path beta = repository.resolve("b/beta/2/beta-2.jar");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(beta.getParent());
        Files.write(alpha, new byte[] {1});
        Files.write(beta, new byte[] {1});
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, alpha + java.io.File.pathSeparator + beta);
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Jdbi jdbi = database();
        module(jdbi, ".");

        var filtered = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, ".", null, "beta",
                        null, null, null, null, 1, 0));

        assertEquals(1, filtered.path("total").asInt());
        assertEquals("b:beta:2", filtered.path("dependencies").get(0).path("id").asText());
    }

    @Test
    void filtersByCoordinatesDirectnessAndScope() throws Exception {
        Path repository = temp.resolve(".m2/repository");
        Path direct = repository.resolve("com/acme/widget/1/widget-1.jar");
        Path transitive = repository.resolve("org/other/helper/2/helper-2.jar");
        Files.createDirectories(direct.getParent());
        Files.createDirectories(transitive.getParent());
        Files.write(direct, new byte[] {1});
        Files.write(transitive, new byte[] {1});
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, direct + java.io.File.pathSeparator + transitive);
        Files.writeString(temp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <dependencies><dependency><groupId>com.acme</groupId>
                    <artifactId>widget</artifactId><version>1</version><scope>runtime</scope>
                  </dependency></dependencies>
                </project>
                """);
        Jdbi jdbi = database();
        module(jdbi, ".");

        var result = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, null,
                        "direct", "acme", "widget", "runtime", 100, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals("com.acme:widget:1",
                result.path("dependencies").get(0).path("id").asText());
    }

    @Test
    void prewarmsAndInvalidatesMetadataWhenClasspathChanges() throws Exception {
        Path repository = temp.resolve(".m2/repository");
        Path alpha = repository.resolve("a/alpha/1/alpha-1.jar");
        Path beta = repository.resolve("b/beta/2/beta-2.jar");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(beta.getParent());
        Files.write(alpha, new byte[] {1});
        Files.write(beta, new byte[] {1});
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, alpha.toString());
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Jdbi jdbi = database();
        module(jdbi, ".");

        ProjectDependencyQueries.prewarm(jdbi, temp);
        var warm = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, null,
                        null, null, null, null, 100, 0));
        assertEquals("hit", warm.path("discovery").path("metadata_cache").asText());

        Files.writeString(classpath, alpha + java.io.File.pathSeparator + beta);
        var refreshed = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, null,
                        null, null, null, null, 100, 0));
        assertEquals("miss", refreshed.path("discovery").path("metadata_cache").asText());
        assertEquals(2, refreshed.path("total").asInt());
    }

    @Test
    void reportsUncoveredTestScopeInsteadOfClaimingAnEmptyAnswerIsComplete() throws Exception {
        Path connector = temp.resolve(
                ".m2/repository/io/casehub/connectors-core/1/connectors-core-1.jar");
        Files.createDirectories(connector.getParent());
        Files.write(connector, new byte[] {1});
        mavenModule("planning", "planning", """
                <dependency><groupId>io.casehub</groupId>
                  <artifactId>persistence-memory</artifactId><version>1</version>
                  <scope>test</scope></dependency>
                """);
        mavenModule("persistence-memory", "persistence-memory", """
                <dependency><groupId>io.casehub</groupId>
                  <artifactId>support-core</artifactId><version>1</version></dependency>
                """);
        mavenModule("support-core", "support-core", """
                <dependency><groupId>io.casehub</groupId>
                  <artifactId>connectors-core</artifactId><version>1</version></dependency>
                """);
        Files.writeString(temp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>io.casehub</groupId><artifactId>root</artifactId><version>1</version>
                  <modules><module>planning</module><module>persistence-memory</module>
                    <module>support-core</module></modules>
                </project>
                """);
        writeClasspath("planning", "");
        writeClasspath("persistence-memory", "");
        writeClasspath("support-core", connector.toString());
        Jdbi jdbi = database();
        module(jdbi, "planning");
        module(jdbi, "persistence-memory");
        module(jdbi, "support-core");

        var result = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, "planning", null, "connectors-core",
                        null, null, null, null, 100, 0));

        assertEquals(1, result.path("total").asInt());
        assertFalse(result.path("answer_complete").asBoolean());
        assertFalse(result.path("discovery").path("requested_source_set_covered").asBoolean());
        assertEquals("main_and_test_runtime",
                result.path("discovery").path("classpath_scope").asText());
        assertEquals("planning", result.path("dependencies").get(0)
                .path("test_runtime_in_modules").get(0).asText());
        assertEquals("planning", result.path("dependencies").get(0)
                .path("inferred_in_modules").get(0).asText());
        assertTrue(result.path("discovery").path("limitations").toString()
                .contains("reactor declarations"));
        assertEquals("partial", result.path("directness").asText());
    }

    @Test
    void usesCapturedTestClasspathForDirectAndTransitiveTestDependencies() throws Exception {
        Path runtime = temp.resolve(".m2/repository/a/runtime/1/runtime-1.jar");
        Path testOnly = temp.resolve(".m2/repository/a/test-helper/1/test-helper-1.jar");
        Files.createDirectories(runtime.getParent());
        Files.createDirectories(testOnly.getParent());
        Files.write(runtime, new byte[] {1});
        Files.write(testOnly, new byte[] {1});
        Files.writeString(temp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <dependencies><dependency><groupId>a</groupId>
                    <artifactId>test-helper</artifactId><version>1</version>
                    <scope>test</scope></dependency></dependencies>
                </project>
                """);
        writeClasspath(".", runtime.toString());
        writeTestClasspath(".", runtime + java.io.File.pathSeparator + testOnly);
        Jdbi jdbi = database();
        module(jdbi, ".");

        var result = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, ".", "test", "test-helper",
                        null, null, null, null, 100, 0));

        assertEquals(1, result.path("total").asInt());
        assertTrue(result.path("answer_complete").asBoolean());
        assertTrue(result.path("discovery").path("requested_source_set_covered").asBoolean());
        assertEquals(".", result.path("dependencies").get(0)
                .path("test_runtime_in_modules").get(0).asText());
        assertEquals(0, result.path("dependencies").get(0)
                .path("main_runtime_in_modules").size());
        assertEquals("test", result.path("dependencies").get(0)
                .path("declared_scopes").get(0).asText());
    }

    @Test
    void debugModeRecordsDependencyDecisionTraceWithoutUsingMcpStdout() throws Exception {
        Path jar = temp.resolve(".m2/repository/a/alpha/1/alpha-1.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});
        writeClasspath(".", jar.toString());
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Jdbi jdbi = database();
        module(jdbi, ".");
        DebugTrace.configure(true, temp);
        DebugTrace.Trace parent = DebugTrace.start("mcp_tool_call");
        try {
            var result = JSON.readTree(new ProjectDependencyQueries()
                    .getProjectDependencies(jdbi, temp, ".", "main", "alpha",
                            null, null, null, "runtime", 100, 0));

            String traceId = result.path("debug").path("trace_id").asText();
            assertFalse(traceId.isBlank());
            Path log = temp.resolve(".quill/debug/quill-debug.jsonl");
            String events = Files.readString(log);
            assertTrue(events.contains(traceId));
            assertTrue(events.contains("\"parent_trace_id\":\"" + parent.id() + "\""));
            assertTrue(events.contains("classpath_loaded"));
            assertTrue(events.contains("filter_summary"));
            assertTrue(events.contains("result_completeness"));
        } finally {
            parent.close();
            DebugTrace.configure(false, temp);
        }
    }

    private void mavenModule(String path, String artifact, String dependencies)
            throws Exception {
        Path directory = temp.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>io.casehub</groupId><artifactId>%s</artifactId><version>1</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(artifact, dependencies));
    }

    private void writeClasspath(String module, String value) throws Exception {
        Path directory = module.equals(".") ? temp : temp.resolve(module);
        Path classpath = directory.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, value);
    }

    private void writeTestClasspath(String module, String value) throws Exception {
        Path directory = module.equals(".") ? temp : temp.resolve(module);
        Path classpath = directory.resolve("target/quill-test-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, value);
    }

    private Jdbi database() {
        return QuillDatabase.create(temp.resolve("index-" + System.nanoTime() + ".db"));
    }

    private static void module(Jdbi jdbi, String module) {
        String path = module.equals(".") ? "src/main/java/App.java"
                : module + "/src/main/java/App.java";
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle,
                                          module, source_set)
                        VALUES (:path, :path, 'java', 'source', 'current', :module, 'main')
                        """).bind("path", path).bind("module", module).execute());
    }
}
