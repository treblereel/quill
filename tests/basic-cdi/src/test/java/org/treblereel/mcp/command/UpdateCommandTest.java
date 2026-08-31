package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpdateCommandTest {

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
    void updateRunsFullInitWhenNoIndexExists() {
        assertFalse(Files.exists(QUILL_DIR.resolve("index.db")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            UpdateCommand cmd = new UpdateCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.run();
        } finally {
            System.setOut(System.out);
        }

        assertTrue(Files.exists(QUILL_DIR.resolve("index.db")),
                "Should create index.db via full init");
        assertTrue(out.toString().contains("No existing index found"),
                "Should print 'no existing index' message");
    }

    @Test
    void updateEarlyExitsWhenNoClassChanges() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.noHooks = true;
        init.run();

        assertTrue(Files.exists(QUILL_DIR.resolve("index.db")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            UpdateCommand cmd = new UpdateCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = out.toString();
        assertTrue(output.contains("up to date"),
                "Should report index is up to date, got: " + output);
    }

    @Test
    void updateReindexesWhenClassFilesAreNewer() throws Exception {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.noHooks = true;
        init.run();

        // Touch a .class file in target/classes to make it newer than indexed_at
        Thread.sleep(1100);
        String sep = PROJECT_ROOT.getFileSystem().getSeparator();
        try (var walk = Files.walk(PROJECT_ROOT)) {
            Path classFile = walk
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> p.toString().contains("target" + sep + "classes"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No .class file found in target/classes"));
            Files.setLastModifiedTime(classFile,
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            UpdateCommand cmd = new UpdateCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = out.toString();
        assertTrue(output.contains("Changes detected"),
                "Should detect changes and re-index, got: " + output);
    }

    @Test
    void updateForceBypassesTimestampCheck() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.noHooks = true;
        init.run();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            UpdateCommand cmd = new UpdateCommand();
            cmd.projectPath = PROJECT_ROOT;
            cmd.force = true;
            cmd.run();
        } finally {
            System.setOut(original);
        }

        String output = out.toString();
        assertTrue(output.contains("Forced full re-index"),
                "Should report forced re-index, got: " + output);
    }
}
