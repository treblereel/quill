package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.QuillDatabase;

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
        assertNull(ProjectInitializer.findDbForHead(PROJECT_ROOT));

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

        assertNotNull(ProjectInitializer.findDbForHead(PROJECT_ROOT),
                "Should create index db via full init");
        assertTrue(out.toString().contains("No existing index found"),
                "Should print 'no existing index' message");
    }

    @Test
    void updateEarlyExitsWhenNoClassChanges() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.indexOnly = true;
        init.run();

        assertNotNull(ProjectInitializer.findDbForHead(PROJECT_ROOT));

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
    void updateIgnoresTimestampOnlyClassChanges() throws Exception {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.indexOnly = true;
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
        assertTrue(output.contains("up to date"),
                "Timestamp-only changes must not trigger re-indexing, got: " + output);
    }

    @Test
    void updateForceBypassesTimestampCheck() {
        InitCommand init = new InitCommand();
        init.projectPath = PROJECT_ROOT;
        init.indexOnly = true;
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

    @Test
    void fingerprintDetectsPomChanges(@TempDir Path project) throws Exception {
        Path classesDir = Files.createDirectories(project.resolve("target/classes"));
        Files.write(classesDir.resolve("Example.class"), new byte[]{1, 2, 3});
        Path pom = Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path db = createFingerprintDatabase(project, classesDir);

        assertFalse(UpdateCommand.hasProjectChanges(project, db));
        Files.writeString(pom, "<project><!-- changed --></project>");
        assertTrue(UpdateCommand.hasProjectChanges(project, db));
    }

    @Test
    void fingerprintDetectsGradleBuildChanges(@TempDir Path project) throws Exception {
        Path classesDir = Files.createDirectories(project.resolve("build/classes/java/main"));
        Files.write(classesDir.resolve("Example.class"), new byte[]{1, 2, 3});
        Path build = Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }");
        Path db = createFingerprintDatabase(project, classesDir);

        assertFalse(UpdateCommand.hasProjectChanges(project, db));
        Files.writeString(build, "plugins { id 'java-library' }");
        assertTrue(UpdateCommand.hasProjectChanges(project, db));
    }

    @Test
    void fingerprintDetectsDeletedClasses(@TempDir Path project) throws Exception {
        Path classesDir = Files.createDirectories(project.resolve("target/classes"));
        Path classFile = Files.write(classesDir.resolve("Example.class"), new byte[]{1, 2, 3});
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path db = createFingerprintDatabase(project, classesDir);

        assertFalse(UpdateCommand.hasProjectChanges(project, db));
        Files.delete(classFile);
        assertTrue(UpdateCommand.hasProjectChanges(project, db));
    }

    @Test
    void fingerprintDetectsClassContentChangeWithSameSizeAndTimestamp(
            @TempDir Path project) throws Exception {
        Path classesDir = Files.createDirectories(project.resolve("target/classes"));
        Path classFile = Files.write(classesDir.resolve("Example.class"), new byte[]{1, 2, 3});
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path db = createFingerprintDatabase(project, classesDir);
        FileTime originalTime = Files.getLastModifiedTime(classFile);

        Files.write(classFile, new byte[]{3, 2, 1});
        Files.setLastModifiedTime(classFile, originalTime);

        assertTrue(UpdateCommand.hasProjectChanges(project, db));
    }

    @Test
    void compileFailureLeavesExistingIndexUntouched(@TempDir Path project) throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        Path db = project.resolve(".quill/nocommit.db");
        QuillDatabase.create(db).useHandle(handle -> handle.createUpdate(
                        "INSERT INTO metadata(key, value) VALUES ('sentinel', 'preserved')")
                .execute());
        writeFailingWrapper(project, BuildSystem.MAVEN);

        UpdateCommand command = new UpdateCommand();
        command.projectPath = project;
        command.compile = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, command::run);
        assertTrue(failure.getMessage().contains("exited with code 7"), failure.getMessage());
        assertEquals("preserved", org.treblereel.mcp.db.IndexReader
                .getMetadata(QuillDatabase.open(db)).get("sentinel"));
    }

    @Test
    void gradleCompileFailureLeavesExistingIndexUntouched(@TempDir Path project) throws Exception {
        Files.createFile(project.resolve("settings.gradle"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }");
        Path db = project.resolve(".quill/nocommit.db");
        QuillDatabase.create(db).useHandle(handle -> handle.createUpdate(
                        "INSERT INTO metadata(key, value) VALUES ('sentinel', 'preserved')")
                .execute());
        writeFailingWrapper(project, BuildSystem.GRADLE);

        UpdateCommand command = new UpdateCommand();
        command.projectPath = project;
        command.compile = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class, command::run);
        assertTrue(failure.getMessage().contains("exited with code 7"), failure.getMessage());
        assertEquals("preserved", org.treblereel.mcp.db.IndexReader
                .getMetadata(QuillDatabase.open(db)).get("sentinel"));
    }

    private Path createFingerprintDatabase(Path project, Path classesDir) {
        String fingerprint = ProjectInitializer.computeStateFingerprint(project, java.util.List.of(classesDir));
        Path db = project.resolve(".quill/nocommit.db");
        QuillDatabase.create(db).useHandle(handle -> handle.createUpdate(
                        "INSERT INTO metadata(key, value) VALUES ('state_fingerprint', :value)")
                .bind("value", fingerprint)
                .execute());
        return db;
    }

    private static void writeFailingWrapper(Path project, BuildSystem buildSystem) throws Exception {
        if (BuildSystem.isWindows()) {
            String name = buildSystem == BuildSystem.MAVEN ? "mvnw.cmd" : "gradlew.bat";
            Files.writeString(project.resolve(name), "@exit /b 7\r\n");
        } else {
            String name = buildSystem == BuildSystem.MAVEN ? "mvnw" : "gradlew";
            Path wrapper = Files.writeString(project.resolve(name), "#!/bin/sh\nexit 7\n");
            wrapper.toFile().setExecutable(true);
        }
    }
}
