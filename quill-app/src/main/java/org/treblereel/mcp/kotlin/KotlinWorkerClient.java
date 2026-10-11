package org.treblereel.mcp.kotlin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** One serialized, bounded compiler process per Quill session. No analysis results are cached. */
public final class KotlinWorkerClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FRAME = 1024 * 1024;
    private static final KotlinWorkerClient SHARED = new KotlinWorkerClient(
            KotlinRuntime::workerCommand, KotlinRuntime::workerIdentity, Duration.ofSeconds(30));
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SHARED::close, "quill-k2-shutdown"));
    }
    @FunctionalInterface interface Command { List<String> get() throws IOException; }
    @FunctionalInterface interface Identity { String get() throws IOException; }
    private final Command command;
    private final Identity identity;
    private final long timeoutNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private final java.util.concurrent.atomic.AtomicInteger closing = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Process process;
    private volatile ExecutorService executor;
    private String runtimeIdentity;
    private List<Path> runtimeFiles = List.of();
    private List<String> runtimeStamp = List.of();
    private DataInputStream input;
    private DataOutputStream output;

    KotlinWorkerClient(Command command, Identity identity, Duration timeout) {
        this.command = command;
        this.identity = identity;
        this.timeoutNanos = timeout.toNanos();
    }
    public static KotlinWorkerClient shared() { return SHARED; }

    public JsonNode query(JsonNode request) {
        boolean acquired = false;
        try {
            acquired = lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!acquired) return status("timeout");
            if (closing.get() != 0) return status("unavailable");
            String current;
            try { current = identity.get(); }
            catch (IOException missing) { stop(); return status("runtime_missing"); }
            if (process != null) {
                boolean changed = !process.isAlive() || !current.equals(runtimeIdentity);
                try { changed |= !stamp(runtimeFiles).equals(runtimeStamp); }
                catch (IOException missing) { changed = true; }
                if (changed) stop();
            }
            if (process == null) {
                List<String> args;
                try { args = new ArrayList<>(command.get()); }
                catch (IOException missing) { return status("runtime_missing"); }
                int cp = args.indexOf("-cp");
                runtimeFiles = cp < 0 ? List.of() : java.util.Arrays.stream(args.get(cp + 1)
                        .split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList();
                runtimeStamp = stamp(runtimeFiles);
                if (closing.get() != 0) return status("unavailable");
                args.add("--serve");
                process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (closing.get() != 0) { stop(); return status("unavailable"); }
                runtimeIdentity = current;
                input = new DataInputStream(process.getInputStream());
                output = new DataOutputStream(process.getOutputStream());
                executor = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "quill-k2-io"); thread.setDaemon(true); return thread;
                });
            }
            byte[] bytes = JSON.writeValueAsBytes(request);
            if (bytes.length > MAX_FRAME) return status("request_limit");
            // Both writing and reading are bounded: a hung child must not block the MCP scheduler.
            DataInputStream responseInput = input;
            DataOutputStream requestOutput = output;
            Future<JsonNode> pending = executor.submit(() -> {
                requestOutput.writeInt(bytes.length); requestOutput.write(bytes); requestOutput.flush();
                int size = responseInput.readInt();
                if (size <= 0 || size > MAX_FRAME) throw new IOException("Invalid response frame");
                byte[] response = responseInput.readNBytes(size);
                if (response.length != size) throw new IOException("Truncated response frame");
                JsonNode result = JSON.readTree(response);
                if (result == null || !result.isObject() || !result.path("status").isTextual()
                        || !result.path("backend").asText().equals("kotlin_k2_fir"))
                    throw new IOException("Invalid worker response");
                return result;
            });
            JsonNode result = pending.get(timeoutNanos, TimeUnit.NANOSECONDS);
            if (result.path("reason").asText().equals("StackOverflowError")) stop();
            return result;
        } catch (TimeoutException timeout) {
            stop(); return status("timeout");
        } catch (InterruptedException interrupted) {
            if (acquired) stop();
            Thread.currentThread().interrupt(); return status("interrupted");
        } catch (Exception failure) {
            if (acquired) stop();
            return status("worker_failed");
        } finally {
            if (acquired) lock.unlock();
        }
    }

    private static List<String> stamp(List<Path> paths) throws IOException {
        var result = new ArrayList<String>();
        for (Path path : paths) {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            result.add(path + ":" + attributes.size() + ":" + attributes.lastModifiedTime()
                    + ":" + attributes.fileKey());
        }
        return result;
    }

    // Called under the request lock. Killing the process unblocks pipe I/O before reuse.
    private void stop() {
        Process child = process;
        if (child != null) {
            child.destroyForcibly();
            try { child.waitFor(1, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        ExecutorService io = executor;
        if (io != null) io.shutdownNow();
        process = null; executor = null; input = null; output = null;
    }

    @Override public void close() {
        closing.incrementAndGet();
        try {
            // Also prevent a cold request from starting a child after shutdown has begun.
            Process child = process;
            if (child != null) child.destroyForcibly();
            lock.lock();
            try { stop(); } finally { lock.unlock(); }
        } finally { closing.decrementAndGet(); }
    }
    private static JsonNode status(String value) { return JSON.createObjectNode().put("status", value); }
}
