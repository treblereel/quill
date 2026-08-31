package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CleanCommandTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void cleanRemovesQuillDirectory() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.noHooks = true;
        init.run();

        assertTrue(Files.exists(QUILL_DIR.resolve("index.db")));

        CleanCommand cmd = new CleanCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();

        assertFalse(Files.exists(QUILL_DIR), ".quill directory should be removed");
    }

    @Test
    void cleanHandlesMissingQuillDirectory() {
        assertFalse(Files.exists(QUILL_DIR));

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
        assertTrue(output.contains("No .quill directory found"),
                "Should report no directory found, got: " + output);
    }
}
