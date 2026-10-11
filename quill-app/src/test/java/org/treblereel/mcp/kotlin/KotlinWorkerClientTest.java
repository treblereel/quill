package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KotlinWorkerClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private List<String> command() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), FakeWorker.class.getName());
    }
    private com.fasterxml.jackson.databind.JsonNode request(String mode) {
        return JSON.createObjectNode().put("mode", mode);
    }
    @Test void reusesProcessAndSerializesConcurrentRequests() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        try (var worker = new KotlinWorkerClient(() -> { starts.incrementAndGet(); return command(); },
                () -> "same", Duration.ofSeconds(5))) {
            long pid = worker.query(request("ok")).path("pid").asLong();
            assertTrue(pid > 0);
            var pool = Executors.newFixedThreadPool(4);
            try {
                var futures = new java.util.ArrayList<Future<?>>();
                for (int i = 0; i < 16; i++) {
                    String token = "request-" + i;
                    futures.add(pool.submit(() -> {
                        var result = worker.query(request(token));
                        assertEquals(token, result.path("echo").asText());
                        assertEquals(pid, result.path("pid").asLong());
                    }));
                }
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            assertEquals(1, starts.get());
            worker.close();
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    @Test void crashesMalformedAndOversizedResponsesRestartCleanly() {
        try (var worker = new KotlinWorkerClient(this::command, () -> "same", Duration.ofSeconds(5))) {
            for (String mode : List.of("exit", "malformed", "oversize", "truncated", "wrong_backend", "zero")) {
                assertEquals("worker_failed", worker.query(request(mode)).path("status").asText(), mode);
                assertEquals("resolved", worker.query(request("ok")).path("status").asText(), mode);
            }
            assertEquals("request_limit", worker.query(request("x".repeat(1024 * 1024))).path("status").asText());
            assertEquals("resolved", worker.query(request("ok")).path("status").asText());
        }
    }
    @Test void exhaustedCompilerIsReapedBeforeTheVeryNextRequest() {
        try (var worker = new KotlinWorkerClient(this::command, () -> "same", Duration.ofSeconds(5))) {
            var failed = worker.query(request("stack_overflow"));
            assertEquals("unavailable", failed.path("status").asText());
            assertEquals("StackOverflowError", failed.path("reason").asText());
            long pid = failed.path("pid").asLong(); assertTrue(pid > 0);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            var next = worker.query(request("ok"));
            assertEquals("resolved", next.path("status").asText());
            assertNotEquals(pid, next.path("pid").asLong());
        }
    }
    @Test void timeoutAndInterruptionDiscardTheOldProtocolStream() throws Exception {
        try (var worker = new KotlinWorkerClient(this::command, () -> "same", Duration.ofSeconds(1))) {
            assertEquals("resolved", worker.query(request("ok")).path("status").asText());
            assertEquals("timeout", worker.query(request("hang")).path("status").asText());
            assertEquals("resolved", worker.query(request("ok")).path("status").asText());
            var started = new CountDownLatch(1);
            var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            Thread thread = new Thread(() -> {
                started.countDown();
                var result = worker.query(request("hang"));
                interrupted.set(Thread.currentThread().isInterrupted() && result.path("status").asText().equals("interrupted"));
            });
            thread.start(); started.await(); Thread.sleep(100); thread.interrupt(); thread.join(3000);
            assertFalse(thread.isAlive()); assertTrue(interrupted.get());
            assertEquals("resolved", worker.query(request("ok")).path("status").asText());
        }
    }
    @Test void runtimeChangeAndMissingRuntimeStopOldWorker() {
        var identity = new java.util.concurrent.atomic.AtomicReference<>("first");
        try (var worker = new KotlinWorkerClient(this::command, () -> {
            if (identity.get() == null) throw new IOException(); return identity.get();
        }, Duration.ofSeconds(5))) {
            long first = worker.query(request("ok")).path("pid").asLong();
            identity.set("second");
            long second = worker.query(request("ok")).path("pid").asLong();
            assertNotEquals(first, second); assertTrue(second > 0);
            identity.set(null);
            assertEquals("runtime_missing", worker.query(request("ok")).path("status").asText());
            assertFalse(ProcessHandle.of(second).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    @Test void stderrFloodCannotBlockResponse() {
        try (var worker = new KotlinWorkerClient(this::command, () -> "same", Duration.ofSeconds(5))) {
            assertEquals("resolved", worker.query(request("stderr")).path("status").asText());
        }
    }
    @Test void shutdownUnblocksActiveReadAndReapsTheChild() throws Exception {
        try (var worker = new KotlinWorkerClient(this::command, () -> "same", Duration.ofSeconds(30))) {
            long pid = worker.query(request("ok")).path("pid").asLong();
            var started = new CountDownLatch(1);
            Thread caller = new Thread(() -> { started.countDown(); worker.query(request("hang")); });
            caller.start(); started.await(); Thread.sleep(100);
            long start = System.nanoTime(); worker.close(); caller.join(3000);
            assertFalse(caller.isAlive());
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    @Test void changedAndDeletedClasspathArtifactsInvalidateTheWorker() throws Exception {
        Path jar = java.nio.file.Files.createTempFile("quill-worker-stamp-", ".jar");
        try {
            java.nio.file.Files.writeString(jar, "first");
            try (var worker = new KotlinWorkerClient(() -> {
                if (!java.nio.file.Files.exists(jar)) throw new IOException();
                var args = new java.util.ArrayList<>(command());
                args.set(2, args.get(2) + java.io.File.pathSeparator + jar); return args;
            }, () -> "same", Duration.ofSeconds(5))) {
                long pid = worker.query(request("ok")).path("pid").asLong();
                java.nio.file.Files.writeString(jar, "different-size");
                long next = worker.query(request("ok")).path("pid").asLong();
                assertTrue(next > 0); assertNotEquals(pid, next);
                java.nio.file.Files.delete(jar);
                assertEquals("runtime_missing", worker.query(request("ok")).path("status").asText());
                assertFalse(ProcessHandle.of(next).map(ProcessHandle::isAlive).orElse(false));
            }
        } finally { java.nio.file.Files.deleteIfExists(jar); }
    }
    @Test void shutdownDuringColdPreparationCannotStartAnAbandonedWorker() throws Exception {
        var preparing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = new KotlinWorkerClient(() -> {
            preparing.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            return command();
        }, () -> "same", Duration.ofSeconds(30))) {
            var result = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
            Thread caller = new Thread(() -> result.set(worker.query(request("hang"))));
            caller.start(); assertTrue(preparing.await(5, TimeUnit.SECONDS));
            Thread closer = new Thread(worker::close); closer.start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (closer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
                assertEquals(Thread.State.WAITING, closer.getState());
            } finally { release.countDown(); }
            closer.join(3000); caller.join(3000);
            assertFalse(closer.isAlive()); assertFalse(caller.isAlive());
            assertEquals("unavailable", result.get().path("status").asText());
        } finally { release.countDown(); }
    }
    public static class FakeWorker {
        public static void main(String[] args) throws Exception {
            var input = new DataInputStream(System.in); var output = new DataOutputStream(System.out);
            while (true) {
                int size;
                try { size = input.readInt(); } catch (EOFException eof) { return; }
                String mode = JSON.readTree(input.readNBytes(size)).path("mode").asText();
                if (mode.equals("exit")) return;
                if (mode.equals("hang")) { Thread.sleep(60_000); return; }
                if (mode.equals("stderr")) System.err.write(new byte[2 * 1024 * 1024]);
                if (mode.equals("oversize")) { output.writeInt(1024 * 1024 + 1); output.flush(); continue; }
                if (mode.equals("zero")) { output.writeInt(0); output.flush(); continue; }
                if (mode.equals("truncated")) { output.writeInt(100); output.write(1); output.flush(); return; }
                if (mode.equals("stack_overflow")) {
                    byte[] response = JSON.writeValueAsBytes(JSON.createObjectNode().put("status", "unavailable")
                            .put("backend", "kotlin_k2_fir").put("reason", "StackOverflowError")
                            .put("pid", ProcessHandle.current().pid()));
                    output.writeInt(response.length); output.write(response); output.flush();
                    // Simulate the race between the flushed response and child shutdown.
                    Thread.sleep(60_000); return;
                }
                byte[] bytes = mode.equals("malformed") ? new byte[]{'!'} : JSON.writeValueAsBytes(JSON.createObjectNode()
                        .put("status", "resolved").put("backend", mode.equals("wrong_backend") ? "other" : "kotlin_k2_fir")
                        .put("pid", ProcessHandle.current().pid()).put("echo", mode));
                output.writeInt(bytes.length); output.write(bytes); output.flush();
            }
        }
    }
}
