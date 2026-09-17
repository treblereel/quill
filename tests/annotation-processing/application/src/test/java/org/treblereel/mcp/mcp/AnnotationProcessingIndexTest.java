package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.treblereel.mcp.command.ProjectIndexStore;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

class AnnotationProcessingIndexTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"))
            .getParent().toAbsolutePath().normalize();
    private static Jdbi jdbi;

    @BeforeAll
    static void indexFixture() {
        assertTrue(ProjectInitializer.initialize(PROJECT_ROOT, true));
        Path database = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        assertNotNull(database);
        jdbi = QuillDatabase.open(database);
    }

    @AfterAll
    static void cleanIndex() throws Exception {
        Path quill = PROJECT_ROOT.resolve(".quill");
        if (!Files.exists(quill)) return;
        try (var walk = Files.walk(quill)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void indexesGeneratedClassesFromMultipleProcessingRounds() {
        var classes = IndexReader.findAllClasses(jdbi);
        var first = classes.stream()
                .filter(cls -> cls.className().endsWith("FirstGenerated"))
                .findFirst().orElseThrow();
        var second = classes.stream()
                .filter(cls -> cls.className().endsWith("SecondGenerated"))
                .findFirst().orElseThrow();

        assertEquals("generated", first.origin());
        assertEquals("generated", second.origin());
        assertTrue(first.sourceFile().contains("target/generated-sources/annotations"));
    }

    @Test
    void exposesConstructorOnlyDependencyAndDistinctFanIn() throws Exception {
        var tools = new QuillTools();
        String result = tools.getRisk(jdbi,
                "org.treblereel.mcp.fixture.application.ConstructorOnlyDependency");
        var root = JSON.readTree(result);

        assertEquals(1, root.path("signals").path("fan_in").path("value").asInt());
        assertEquals(1, root.path("signals").path("fan_in").path("edges").asInt());
    }

    @Test
    void resolvesCurrentClassByProjectPath() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi,
                "application/src/main/java/org/treblereel/mcp/fixture/application/ConstructorOnlyDependency.java",
                "both", 1);
        var root = JSON.readTree(result);

        assertEquals("org.treblereel.mcp.fixture.application.ConstructorOnlyDependency",
                root.path("target").asText());
        assertEquals(1, root.path("metrics").path("fan_in").asInt());
        assertEquals(1, root.path("metrics").path("incoming_edges").asInt());
        assertEquals(1, root.path("metrics").path("fan_in_breakdown")
                .path("source").path("classes").asInt());
    }

    @Test
    void inventoriesAnnotationProcessorServiceDescriptor() {
        assertTrue(IndexReader.findFileByPath(jdbi,
                "processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor")
                .filter(file -> file.kind().equals("service_descriptor"))
                .isPresent());
    }

    @Test
    void assessesAnnotationProcessorServiceDescriptorAsHighRiskFile() throws Exception {
        var result = JSON.readTree(new QuillTools().getRisk(jdbi,
                "processor/src/main/resources/META-INF/services/"
                        + "javax.annotation.processing.Processor"));

        assertEquals("file", result.path("target_type").asText());
        assertEquals("service_descriptor", result.path("kind").asText());
        assertTrue(result.path("risk_score").asDouble() >= 6.0);
        assertEquals(10, result.path("signals").path("file_criticality")
                .path("value").asInt());
        assertTrue(result.path("recommendation").asText().contains("provider membership"));
    }

    @Test
    void nativeMcpExposesClassAndFileRisk() throws Exception {
        Path repositoryRoot = PROJECT_ROOT.getParent().getParent();
        Path nativeImage = repositoryRoot.resolve(BuildSystem.isWindows()
                ? "quill-app/target/quill.exe" : "quill-app/target/quill");
        Assumptions.assumeTrue(Files.isExecutable(nativeImage),
                "native image is only present in the native quality gate");

        Process process = new ProcessBuilder(nativeImage.toString(), "--mcp", "--project",
                PROJECT_ROOT.toString()).directory(PROJECT_ROOT.toFile()).start();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stderrReader = Thread.startVirtualThread(() -> {
            try {
                process.getErrorStream().transferTo(stderr);
            } catch (Exception ignored) {
                // Process shutdown closes the stream.
            }
        });
        try (BufferedWriter input = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream()));
                BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
            input.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}\n");
            input.flush();
            assertTrue(readResponse(output, 1).path("result").isObject());
            input.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\","
                    + "\"params\":{}}\n");
            input.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"assess_change_risk\",\"arguments\":{"
                    + "\"target\":\"org.treblereel.mcp.fixture.application."
                    + "ConstructorOnlyDependency\"}}}\n");
            input.flush();

            var response = readResponse(output, 2);
            assertNoTextPayload(response.path("result"));
            var result = response.path("result").path("structuredContent");
            assertEquals(1, result.path("signals").path("fan_in").path("value").asInt());
            assertEquals(1, result.path("signals").path("fan_in").path("edges").asInt());

            input.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"assess_change_risk\",\"arguments\":{"
                    + "\"target\":\"processor/src/main/resources/META-INF/services/"
                    + "javax.annotation.processing.Processor\"}}}\n");
            input.flush();
            var fileResponse = readResponse(output, 3);
            assertNoTextPayload(fileResponse.path("result"));
            var fileRisk = fileResponse.path("result").path("structuredContent");
            assertEquals("file", fileRisk.path("target_type").asText());
            assertEquals("service_descriptor", fileRisk.path("kind").asText());
            assertTrue(fileRisk.path("risk_score").asDouble() >= 6.0);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            stderrReader.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertTrue(!stderr.toString().contains("restricted method"), stderr.toString());
    }

    private static com.fasterxml.jackson.databind.JsonNode readResponse(
            BufferedReader output, int expectedId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (!output.ready()) {
                Thread.sleep(10);
                continue;
            }
            String line = output.readLine();
            if (line == null) break;
            var json = JSON.readTree(line);
            if (json.path("id").asInt(-1) == expectedId) return json;
        }
        throw new AssertionError("Timed out waiting for MCP response " + expectedId);
    }

    private static void assertNoTextPayload(com.fasterxml.jackson.databind.JsonNode result) {
        for (var item : result.path("content")) {
            assertTrue(!"text".equals(item.path("type").asText())
                            || item.path("text").asText().isBlank(),
                    "Structured result must not duplicate its payload as text: "
                            + result.path("content"));
        }
    }
}
