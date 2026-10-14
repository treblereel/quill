package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One bounded child JVM with a two-module compiler-model cache. */
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
    private String jdkHome;

    JavaWorkerClient() { this(Duration.ofSeconds(25), "org.treblereel.mcp.javac.JavaWorker"); }
    JavaWorkerClient(Duration timeout, String workerMain) { this.timeoutMillis = timeout.toMillis(); this.workerMain = workerMain; }

    synchronized JsonNode query(JsonNode request) {
        if (!request.has("sources")) return request;
        try {
            String nextJdk = request.path("jdk_home").asText();
            if (process == null || !process.isAlive() || !nextJdk.equals(jdkHome)) {
                close(); start(request); jdkHome = nextJdk;
            }
            String key = request.path("module") + ":" + request.path("source_set") + ":" + request.path("release") + ":"
                    + request.path("source") + ":" + request.path("target") + ":" + request.path("java_roots") + ":"
                    + request.path("classpath") + ":" + request.path("settings_sha256");
            Properties settings = new Properties();
            for (String property : List.of("release", "source", "target"))
                if (request.has(property)) settings.setProperty(property, request.path(property).asText());
            copy(settings, "roots", request.path("java_roots")); copy(settings, "classpath", request.path("classpath"));
            var serialized = new StringWriter(); settings.store(serialized, "Quill Java inputs");
            input.write(encode(key)); input.write('\t'); input.write(encode(serialized.toString())); input.write('\t');
            input.write(encode(request.path("file").asText()));
            input.write('\t'); input.write(Integer.toString(request.path("offset").asInt())); input.newLine(); input.flush();
            String line = read();
            if (line == null) throw new EOFException();
            String[] parts = line.split("\t", -1);
            if (request.path("offset").asInt() == -2) return references(parts);
            if (parts.length != 12) throw new IOException("Invalid worker response");
            var result = JSON.createObjectNode().put("status", parts[0]).put("backend", "javac");
            String[] keys = {"owner", "kind", "name", "descriptor", "source_file"};
            for (int i = 0; i < keys.length; i++) if (!parts[i + 1].equals("-"))
                result.put(keys[i], new String(Base64.getDecoder().decode(parts[i + 1]), StandardCharsets.UTF_8));
            String[] positions = {"source_line", "source_column", "source_end_line", "source_end_column"};
            for (int i = 0; i < positions.length; i++) {
                int value = Integer.parseInt(parts[i + 6]);
                if (value > 0) result.put(positions[i], value);
            }
            result.put("generation", Long.parseLong(parts[10]));
            if (!Set.of("true", "false").contains(parts[11])) throw new IOException("Invalid reuse flag");
            result.put("reused", Boolean.parseBoolean(parts[11])); return result;
        } catch (InterruptedException interrupted) {
            close(); Thread.currentThread().interrupt(); return status("interrupted");
        } catch (TimeoutException timeout) { close(); return status("timeout"); }
        catch (Exception failure) { close(); return status("worker_unavailable"); }
    }

    private void start(JsonNode request) throws Exception {
        directory = Files.createTempDirectory("quill-java-worker-");
        Path jar = directory.resolve("worker.jar");
        try (var resource = JavaWorkerClient.class.getResourceAsStream("/java-worker/worker.jar")) {
            if (resource == null) throw new FileNotFoundException("Embedded Java worker missing");
            Files.copy(resource, jar);
        }
        Path java = Path.of(request.path("jdk_home").asText()).resolve("bin/java");
        var builder = new ProcessBuilder(java.toString(), "-Xmx512m", "-Xss8m", "-cp", jar.toString(), workerMain);
        // JVM options inherited from the host must not load agents into the selected compiler.
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        reader = Executors.newSingleThreadExecutor(task -> { var t = new Thread(task, "quill-java-worker-read"); t.setDaemon(true); return t; });
        if (!"QUILL_JAVA_4".equals(read())) throw new IOException("Worker protocol mismatch");
    }
    private static JsonNode references(String[] parts) throws IOException {
        if (parts.length != 2 || !parts[0].equals("REFERENCES")) throw new IOException("Invalid references response");
        Properties values = new Properties();
        values.load(new StringReader(new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8)));
        int count = Integer.parseInt(values.getProperty("count"));
        if (count < 0 || count > 4096) throw new IOException("References limit");
        var result = JSON.createObjectNode().put("status", values.getProperty("status")).put("backend", "javac")
                .put("generation", Long.parseLong(values.getProperty("generation")));
        for (String flag : List.of("reused", "truncated")) {
            if (!Set.of("true", "false").contains(values.getProperty(flag))) throw new IOException("Invalid references flag");
            result.put(flag, Boolean.parseBoolean(values.getProperty(flag)));
        }
        if (values.containsKey("source_hash")) result.put("source_hash", values.getProperty("source_hash"));
        var references = result.putArray("references");
        String[] keys = {"owner", "kind", "name", "descriptor", "caller_owner", "caller_name", "caller_descriptor", "access"};
        String[] coordinates = {"line", "column", "end_line", "end_column"};
        for (int i = 0; i < count; i++) {
            String[] reference = values.getProperty("reference." + i).split("\t", -1);
            if (reference.length != 12) throw new IOException("Invalid reference");
            var node = references.addObject();
            for (int j = 0; j < keys.length; j++) node.put(keys[j], reference[j]);
            for (int j = 0; j < coordinates.length; j++) {
                int coordinate = Integer.parseInt(reference[j + 8]);
                if (coordinate < 1) throw new IOException("Invalid reference position");
                node.put(coordinates[j], coordinate);
            }
        }
        return result;
    }
    private static String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
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
        jdkHome = null;
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
