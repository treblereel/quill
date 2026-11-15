package org.treblereel.mcp.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_ERR)
class QuillLogTest {

    private final PrintStream original = System.err;

    @AfterEach
    void restore() {
        System.setErr(original);
        QuillLog.setLevel(QuillLog.Level.INFO);
    }

    @Test
    void infoAndWarnKeepTheQuillPrefixOnTheCurrentStderr() {
        String output = capture(QuillLog.Level.INFO, () -> {
            QuillLog.info("Indexing demo");
            QuillLog.warn("dependency index partial");
            QuillLog.debug("hidden");
        });
        assertEquals("[quill] Indexing demo\n[quill] Warning: dependency index partial\n",
                output.replace(System.lineSeparator(), "\n"));
    }

    @Test
    void levelsFilterAndOffSilencesEverything() {
        assertEquals("[quill] Warning: kept\n", capture(QuillLog.Level.WARN, () -> {
            QuillLog.info("dropped");
            QuillLog.warn("kept");
        }).replace(System.lineSeparator(), "\n"));
        assertEquals("", capture(QuillLog.Level.OFF, () -> QuillLog.warn("dropped")));
    }

    @Test
    void swallowedNamesTheCatchSiteOnlyAtDebug() {
        assertEquals("", capture(QuillLog.Level.INFO, () -> QuillLog.swallowed(new IllegalStateException("x"))));
        String output = capture(QuillLog.Level.DEBUG, this::fallBack);
        assertTrue(output.startsWith("[quill] debug: QuillLogTest.fallBack:"), output);
        assertTrue(output.contains("fell back after IllegalStateException: index unreadable"), output);
    }

    @Test
    void levelComesFromQuillLogLevelOrTheDebugSwitch() {
        assertEquals(QuillLog.Level.INFO, QuillLog.parse(null, null));
        assertEquals(QuillLog.Level.WARN, QuillLog.parse(" warn ", null));
        assertEquals(QuillLog.Level.INFO, QuillLog.parse("loud", null));
        assertEquals(QuillLog.Level.DEBUG, QuillLog.parse("off", "true"));
        assertEquals(QuillLog.Level.OFF, QuillLog.parse("off", "0"));
    }

    private void fallBack() {
        try {
            throw new IllegalStateException("index unreadable");
        } catch (RuntimeException failure) {
            QuillLog.swallowed(failure);
        }
    }

    private String capture(QuillLog.Level level, Runnable action) {
        var buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        QuillLog.setLevel(level);
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
