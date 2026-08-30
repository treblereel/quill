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
    static final Path JOKER_DIR = PROJECT_ROOT.resolve(".joker");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(JOKER_DIR)) {
            try (var walk = Files.walk(JOKER_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void cleanRemovesJokerDirectory() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.noHooks = true;
        init.run();

        assertTrue(Files.exists(JOKER_DIR.resolve("index.db")));

        CleanCommand cmd = new CleanCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();

        assertFalse(Files.exists(JOKER_DIR), ".joker directory should be removed");
    }

    @Test
    void cleanHandlesMissingJokerDirectory() {
        assertFalse(Files.exists(JOKER_DIR));

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
        assertTrue(output.contains("No .joker directory found"),
                "Should report no directory found, got: " + output);
    }
}
