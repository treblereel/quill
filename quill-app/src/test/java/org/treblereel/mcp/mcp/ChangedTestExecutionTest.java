package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Apollo AQ-049: new tests that never ran were reported as "verified true" after a test-compile. */
class ChangedTestExecutionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "portal/src/test/java/p/ValidatorTest.java";
    private static final Path REPORT = Path.of("portal/target/surefire-reports/TEST-p.ValidatorTest.xml");

    @TempDir Path root;

    private ObjectNode changes(String status, String... paths) {
        ObjectNode changes = JSON.createObjectNode();
        for (String path : paths) changes.withArray("changes").addObject().put("path", path).put("status", status);
        return changes;
    }

    private void write(String path, String text, Instant modified) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private static String suite(int tests, int failures) {
        return "<?xml version=\"1.0\"?><testsuite name=\"p.ValidatorTest\" time=\"0.1\" tests=\"" + tests
                + "\" errors=\"0\" skipped=\"0\" failures=\"" + failures + "\"></testsuite>";
    }

    @Test
    void aChangedTestWithoutAReportHasNotRun() throws Exception {
        write(SOURCE, "class ValidatorTest {}", Instant.parse("2026-11-14T10:00:00Z"));
        var result = ChangedTestExecution.inspect(root, changes("modified", SOURCE, "portal/src/main/java/p/Validator.java"));
        assertEquals(List.of("p.ValidatorTest"), result.notExecuted());
        assertEquals(1, result.evidence().path("changed_test_classes").asInt(), "production sources are not test classes");
        assertTrue(result.blocking());
    }

    @Test
    void aReportFromBeforeTheChangeDoesNotCount() throws Exception {
        write(REPORT.toString(), suite(12, 0), Instant.parse("2026-11-14T09:00:00Z"));
        write(SOURCE, "class ValidatorTest {}", Instant.parse("2026-11-14T10:00:00Z"));
        var result = ChangedTestExecution.inspect(root, changes("modified", SOURCE));
        assertEquals("report_predates_change", result.evidence().path("classes").get(0).path("status").asText());
        assertTrue(result.blocking());
    }

    @Test
    void aLaterPassingReportClearsAndAFailingOneBlocks() throws Exception {
        write(SOURCE, "class ValidatorTest {}", Instant.parse("2026-11-14T10:00:00Z"));
        write(REPORT.toString(), suite(13, 0), Instant.parse("2026-11-14T10:05:00Z"));
        var passed = ChangedTestExecution.inspect(root, changes("added", SOURCE));
        assertFalse(passed.blocking(), passed.evidence().toString());
        assertEquals(13, passed.evidence().path("classes").get(0).path("tests").asInt());

        write(REPORT.toString(), suite(13, 2), Instant.parse("2026-11-14T10:06:00Z"));
        var failed = ChangedTestExecution.inspect(root, changes("added", SOURCE));
        assertEquals(List.of("p.ValidatorTest"), failed.failed());
    }

    @Test
    void deletedTestsAndProductionOnlyChangesAreIgnored() throws Exception {
        var result = ChangedTestExecution.inspect(root, changes("deleted", SOURCE));
        assertFalse(result.blocking());
        assertEquals(0, result.evidence().path("changed_test_classes").asInt());
    }
}
