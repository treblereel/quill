package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

class MultiModuleCommandsTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @BeforeEach
    void init() {
        ProjectInitializer.initialize(PROJECT_ROOT, true);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void projectRootFinderAcceptsAggregatorRoot() {
        Path result = ProjectRootFinder.find(PROJECT_ROOT);
        assertEquals(PROJECT_ROOT.toAbsolutePath().normalize(), result);
    }

    @Test
    void updateWorksFromAggregatorRoot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            UpdateCommand cmd = new UpdateCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.call();
        } finally {
            System.setOut(original);
        }

        String output = out.toString();
        assertTrue(output.contains("up to date"),
                "Should report index is up to date, got: " + output);
    }

    @Test
    void statusWorksFromAggregatorRoot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            StatusCommand cmd = new StatusCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.call();
        } finally {
            System.setOut(original);
        }

        String output = out.toString();
        assertTrue(output.contains("5") || output.contains("classes"),
                "Status should report indexed classes, got: " + output);
    }

    @Test
    void cleanRemovesIndexDataFromAggregatorRoot() {
        assertTrue(Files.exists(QUILL_DIR));

        CleanCommand cmd = new CleanCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.call();

        assertNull(ProjectIndexStore.findDbForHead(PROJECT_ROOT));
        assertFalse(Files.exists(QUILL_DIR));
    }

    @Test
    void reindexProducesConsistentIds() {
        ProjectInitializer.initialize(PROJECT_ROOT, true);

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        Jdbi jdbi = QuillDatabase.open(dbPath);
        var classes = IndexReader.findAllClasses(jdbi);
        assertEquals(5, classes.size(), "Re-index should not duplicate classes");

        var classIds = classes.stream().map(c -> c.id()).collect(java.util.stream.Collectors.toSet());
        for (var bean : IndexReader.findBeans(jdbi, null)) {
            assertTrue(classIds.contains(bean.classId()),
                    "After re-index, bean classId " + bean.classId() + " must be valid");
        }
    }
}
