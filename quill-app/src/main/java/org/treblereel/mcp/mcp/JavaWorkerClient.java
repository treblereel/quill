package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One bounded child JVM; changing module/configuration evicts the previous model. */
final class JavaWorkerClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final long timeoutMillis;
    private final String workerMain;
    private Process process;
    private BufferedWriter input;
    private ExecutorService reader;
    private Future<String> response;
    private BufferedReader output;
    private Path directory;
    private String configuration;

    JavaWorkerClient() { this(Duration.ofSeconds(25), "org.treblereel.mcp.javac.JavaWorker"); }
    JavaWorkerClient(Duration timeout, String workerMain) { this.timeoutMillis = timeout.toMillis(); this.workerMain = workerMain; }

    synchronized JsonNode query(JsonNode request) {
        if (!request.has("sources")) return request;
        try {
            String next = request.path("jdk_home") + ":" + request.path("release") + ":" + request.path("source") + ":" + request.path("target") + ":"
                    + request.path("java_roots") + ":" + request.path("classpath") + ":" + request.path("settings_sha256");
            if (process == null || !process.isAlive() || !next.equals(configuration)) {
                close(); start(request); configuration = next;
            }
            input.write(Base64.getEncoder().encodeToString(request.path("file").asText().getBytes(StandardCharsets.UTF_8)));
            input.write('\t'); input.write(Integer.toString(request.path("offset").asInt())); input.newLine(); input.flush();
            String line = read();
            if (line == null) throw new EOFException();
            String[] parts = line.split("\t", -1);
            if (parts.length != 8) throw new IOException("Invalid worker response");
            var result = JSON.createObjectNode().put("status", parts[0]).put("backend", "javac");
            String[] keys = {"owner", "kind", "name", "descriptor", "source_file"};
            for (int i = 0; i < keys.length; i++) if (!parts[i + 1].equals("-"))
                result.put(keys[i], new String(Base64.getDecoder().decode(parts[i + 1]), StandardCharsets.UTF_8));
            result.put("generation", Long.parseLong(parts[6]));
            if (!Set.of("true", "false").contains(parts[7])) throw new IOException("Invalid reuse flag");
            result.put("reused", Boolean.parseBoolean(parts[7])); return result;
        } catch (InterruptedException interrupted) {
            close(); Thread.currentThread().interrupt(); return status("interrupted");
        } catch (TimeoutException timeout) { close(); return status("timeout"); }
        catch (Exception failure) { close(); return status("worker_unavailable"); }
    }

    private void start(JsonNode request) throws Exception {
        directory = Files.createTempDirectory("quill-java-worker-");
        Path jar = directory.resolve("worker.jar"), config = directory.resolve("settings.properties");
        try (var resource = JavaWorkerClient.class.getResourceAsStream("/java-worker/worker.jar")) {
            if (resource == null) throw new FileNotFoundException("Embedded Java worker missing");
            Files.copy(resource, jar);
        }
        Properties settings = new Properties();
        for (String key : List.of("release", "source", "target")) if (request.has(key)) settings.setProperty(key, request.path(key).asText());
        copy(settings, "roots", request.path("java_roots")); copy(settings, "classpath", request.path("classpath"));
        try (var writer = Files.newBufferedWriter(config)) { settings.store(writer, "Quill Java inputs"); }
        Path java = Path.of(request.path("jdk_home").asText()).resolve("bin/java");
        var builder = new ProcessBuilder(java.toString(), "-Xmx512m", "-Xss8m", "-cp", jar.toString(), workerMain, config.toString());
        // JVM options inherited from the host must not load agents into the selected compiler.
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        reader = Executors.newSingleThreadExecutor(task -> { var t = new Thread(task, "quill-java-worker-read"); t.setDaemon(true); return t; });
        if (!"QUILL_JAVA_1".equals(read())) throw new IOException("Worker protocol mismatch");
    }
    private static void copy(Properties settings, String prefix, JsonNode values) {
        settings.setProperty(prefix + ".count", Integer.toString(values.size()));
        for (int i = 0; i < values.size(); i++) settings.setProperty(prefix + "." + i, values.get(i).asText());
    }
    private String read() throws Exception {
        BufferedReader current = output;
        response = reader.submit(current::readLine);
        try { return response.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        finally { response = null; }
    }
    private static JsonNode status(String value) { return JSON.createObjectNode().put("status", value).put("backend", "javac"); }
    synchronized boolean alive() { return process != null && process.isAlive(); }
    synchronized void killForTest() { if (process != null) process.destroyForcibly(); }
    @Override public synchronized void close() {
        configuration = null;
        if (process != null) {
            process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            process = null;
        }
        if (response != null) response.cancel(true);
        if (reader != null) { reader.shutdownNow(); reader = null; }
        try { if (input != null) input.close(); } catch (IOException ignored) { }
        try { if (output != null) output.close(); } catch (IOException ignored) { }
        input = null; output = null;
        if (directory != null) {
            try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
            catch (IOException ignored) { }
            try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            directory = null;
        }
    }
}
