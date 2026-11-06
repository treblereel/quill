package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The server closes its workers while exiting, which can be the first use of their classes. */
class LateClassInitializationTest {

    /** Runs in its own JVM: initializes the classes for the first time once shutdown has begun. */
    static final class Probe {
        public static void main(String[] args) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                KotlinSemanticQueries.closeWarmup();
                JavaSemanticQueries.close();
                org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().close();
                System.out.println("closed");
            }));
        }
    }

    @Test
    void workersInitializedDuringShutdownCloseWithoutError() throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).start();
        assertEquals(true, process.waitFor(60, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(output.contains("ExceptionInInitializerError"), output);
        assertEquals(true, output.contains("closed"), output);
    }
}
