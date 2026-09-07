package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CleanCommandTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void cleanRemovesIndexDataAndRetainsLifecycleLock() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.indexOnly = true;
        init.run();

        assertNotNull(ProjectInitializer.findDbForHead(PROJECT_ROOT));

        CleanCommand cmd = new CleanCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();

        assertNull(ProjectInitializer.findDbForHead(PROJECT_ROOT));
        assertTrue(Files.isRegularFile(QUILL_DIR.resolve(ProjectIndexLock.LOCK_FILE)));
        try (var files = Files.list(QUILL_DIR)) {
            assertEquals(List.of(QUILL_DIR.resolve(ProjectIndexLock.LOCK_FILE)), files.toList());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void cleanHandlesMissingQuillDirectory() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            CleanCommand cmd = new CleanCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = out.toString();
        assertTrue(output.contains("No index data found"),
                "Should report no directory found, got: " + output);
    }
}
