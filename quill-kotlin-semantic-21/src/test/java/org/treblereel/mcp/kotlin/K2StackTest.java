package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class K2StackTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    private ObjectNode chain() throws Exception {
        var request = JSON.createObjectNode().put("module", temp.toString()).put("source_set", "main");
        var sources = request.putArray("sources");
        // Forward dependencies force recursive implicit return-type resolution across files.
        for (int i = 0; i < 500; i++) {
            Path source = temp.resolve(String.format("Source%05d.kt", i));
            Files.writeString(source, "package probe\nfun f" + i + "(x: Int) = "
                    + (i < 499 ? "f" + (i + 1) + "(x)" : "x"));
            sources.add(source.toString());
        }
        Path caller = temp.resolve("Caller.kt"); String text = "package probe\nfun run() = f0(1)";
        Files.writeString(caller, text); sources.add(caller.toString());
        request.put("file", caller.toString()).put("offset", text.indexOf("f0("));
        request.putArray("classpath").add(Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return request;
    }

    private Process worker(String stack) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512m", "-Xss" + stack, "-cp", System.getProperty("java.class.path"),
                SemanticWorker.class.getName(), "--serve")
                .redirectError(temp.resolve("worker-" + stack + ".log").toFile()).start();
    }

    private JsonNode query(Process worker, ObjectNode request) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> {
                byte[] bytes = JSON.writeValueAsBytes(request);
                var output = new DataOutputStream(worker.getOutputStream());
                output.writeInt(bytes.length); output.write(bytes); output.flush();
                var input = new DataInputStream(worker.getInputStream()); int size = input.readInt();
                if (size <= 0 || size > 1024 * 1024) throw new IOException("Invalid response size: " + size);
                byte[] response = input.readNBytes(size);
                if (response.length != size) throw new EOFException("Truncated response");
                return JSON.readTree(response);
            }).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) worker.destroyForcibly();
        }
    }

    private void resolved(JsonNode result, String descriptor, boolean reused) {
        assertEquals("resolved", result.path("status").asText(), result.toString());
        assertEquals(descriptor, result.path("descriptor").asText(), result.toString());
        assertEquals(reused, result.path("analysis_reused").asBoolean(), result.toString());
    }

    @Test void deepInferredReturnsResolveReuseAndInvalidateOnWorkerStack() throws Exception {
        var request = chain(); Process worker = worker("8m");
        try {
            var first = query(worker, request); resolved(first, "(I)I", false);
            var reused = query(worker, request); resolved(reused, "(I)I", true);
            assertEquals(first.path("analysis_generation"), reused.path("analysis_generation"));
            Files.writeString(temp.resolve("Source00499.kt"), "package probe\nfun f499(x: Int) = true");
            var changed = query(worker, request); resolved(changed, "(I)Z", false);
            assertTrue(changed.path("analysis_generation").asLong() > first.path("analysis_generation").asLong());
            resolved(query(worker, request), "(I)Z", true);
        } finally { worker.destroyForcibly(); assertTrue(worker.waitFor(5, TimeUnit.SECONDS)); }
    }

    @Test void exhaustedStackReturnsFallbackAndExitsBeforeReusingCompilerState() throws Exception {
        var request = chain(); Process worker = worker("256k");
        try {
            var failed = query(worker, request);
            assertEquals("unavailable", failed.path("status").asText(), failed.toString());
            assertEquals("StackOverflowError", failed.path("reason").asText(), failed.toString());
            assertFalse(failed.has("descriptor")); assertFalse(failed.has("analysis_reused"));
            assertTrue(worker.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, worker.exitValue());
            assertEquals(-1, worker.getInputStream().read());
        } finally { worker.destroyForcibly(); worker.waitFor(5, TimeUnit.SECONDS); }
        Process restarted = worker("8m");
        try { resolved(query(restarted, request), "(I)I", false); }
        finally { restarted.destroyForcibly(); assertTrue(restarted.waitFor(5, TimeUnit.SECONDS)); }
    }
}
