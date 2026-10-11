package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.treblereel.mcp.kotlin.KotlinRuntime;
import org.treblereel.mcp.kotlin.KotlinWorkerClient;

/** Measures the production JVM coordinator and external K2, excluding MCP transport/indexing.
 * Compile into a temporary directory with the app all.jar on the classpath, then run
 * org.treblereel.mcp.mcp.QuillKotlinWarmupBenchmark <generated-files> <samples> <output.json>.
 * No foreground query is issued between saving and observing background completion.
 */
public final class QuillKotlinWarmupBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Completion(long started, long finished, JsonNode result) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected files, samples, output.json");
        int files = Integer.parseInt(args[0]), samples = Integer.parseInt(args[1]);
        if (files < 1 || files > 4998 || samples < 1) throw new IllegalArgumentException("Invalid counts");
        Path module = Files.createTempDirectory("quill-warmup-latency-");
        Path sourceRoot = module.resolve("src/main/kotlin"); Files.createDirectories(sourceRoot);
        Path definitions = sourceRoot.resolve("Definitions.kt"), caller = sourceRoot.resolve("Caller.kt");
        var sources = new ArrayList<Path>();
        for (int i = 0; i < files; i++) {
            Path source = sourceRoot.resolve(String.format("Source%05d.kt", i));
            Files.writeString(source, "package probe\nfun f" + i + "(x: Int): Int = "
                    + (i == 0 ? "x" : "f" + (i - 1) + "(x)")); sources.add(source);
        }
        Files.writeString(definitions, "package probe\nfun pick() = 1000"); sources.add(definitions);
        String body = "package probe\nfun run() = pick()\nfun chain() = f" + (files - 1) + "(1)";
        Files.writeString(caller, body); sources.add(caller);
        Path stdlib = KotlinRuntime.prepare().stream().filter(path -> path.getFileName().toString()
                .startsWith("kotlin-stdlib-")).findFirst().orElseThrow();
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path captured = module.resolve("target/quill-classpath.txt"); Files.createDirectories(captured.getParent());
        Files.writeString(captured, stdlib.toString());
        ObjectNode base = JSON.createObjectNode().put("module", module.toString()).put("source_set", "main")
                .put("file", caller.toString()).put("captured_classpath", captured.toString()).put("profile_analysis", true);
        var array = base.putArray("sources"); sources.forEach(path -> array.add(path.toString()));
        base.putArray("java_roots"); base.putArray("classpath").add(stdlib.toString());
        Supplier<JsonNode> foreground = () -> base.deepCopy().put("offset", body.indexOf("pick()"));
        Supplier<JsonNode> background = () -> base.deepCopy().put("offset", -1);
        var completions = new LinkedBlockingQueue<Completion>();
        var report = JSON.createObjectNode().put("scope", "JVM coordinator, filesystem watcher and external K2; excludes MCP/indexing")
                .put("java", System.getProperty("java.version")).put("worker_java", KotlinRuntime.workerCommand().get(0))
                .put("os", System.getProperty("os.name")).put("sources", sources.size()).put("samples_per_mode", samples);
        try (var service = FileSystems.getDefault().newWatchService()) {
            report.put("jdk_default_watch_service", service.getClass().getName());
        }
        var modes = report.putArray("modes");
        try (var coordinator = new KotlinWarmupCoordinator(request -> {
            long started = System.nanoTime();
            JsonNode result = KotlinWorkerClient.shared().query(request);
            if (request.path("offset").asInt() == -1) completions.add(new Completion(started, System.nanoTime(), result));
            return result;
        }, Duration.ofMillis(150))) {
            JsonNode current = coordinator.query(module.toString(), foreground, background);
            verify(current, "()I", false);
            report.put("input_detection", coordinator.watchBackend());
            report.put("watched_directories", coordinator.watchedDirectories().size());
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long idleStarted = System.nanoTime(), cpuStarted = os.getProcessCpuTime();
            Thread.sleep(2000);
            report.put("startup_idle_observation_ms", (System.nanoTime() - idleStarted) / 1_000_000.0)
                    .put("startup_idle_parent_cpu_ms", (os.getProcessCpuTime() - cpuStarted) / 1_000_000.0);
            boolean booleanResult = false;
            for (int saves : new int[]{1, 5}) {
                var mode = modes.addObject().put("saves_per_burst", saves).put("interval_ms", saves == 1 ? 0 : 20);
                var observations = mode.putArray("samples");
                var warmTimes = new ArrayList<Double>(); var queryTimes = new ArrayList<Double>();
                for (int round = 0; round < samples; round++) {
                    completions.clear(); long previous = current.path("analysis_generation").asLong();
                    booleanResult = !booleanResult; long firstSave = System.nanoTime(), lastSave = firstSave;
                    for (int save = 0; save < saves; save++) {
                        // Intermediate values change contents; final type alternates each round.
                        String value = save == saves - 1 && booleanResult ? "true" : Integer.toString(1000 + round * saves + save);
                        Files.writeString(definitions, "package probe\nfun pick() = " + value);
                        lastSave = System.nanoTime();
                        if (save < saves - 1) Thread.sleep(20);
                    }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); Completion completed;
                    int attempts = 0;
                    do {
                        long remaining = deadline - System.nanoTime();
                        completed = remaining > 0 ? completions.poll(remaining, TimeUnit.NANOSECONDS) : null;
                        if (completed == null) throw new IllegalStateException("Background warmup did not complete without a query");
                        attempts++;
                        if (completed.result.path("status").asText().equals("inputs_changed")) continue;
                        if (!completed.result.path("status").asText().equals("unresolved"))
                            throw new IllegalStateException(completed.result.toString());
                    } while (completed.result.path("analysis_generation").asLong() <= previous);
                    double elapsed = (completed.finished - lastSave) / 1_000_000.0;
                    long started = System.nanoTime(); current = coordinator.query(module.toString(), foreground, background);
                    double query = (System.nanoTime() - started) / 1_000_000.0;
                    verify(current, booleanResult ? "()Z" : "()I", true);
                    if (!current.path("analysis_generation").equals(completed.result.path("analysis_generation")))
                        throw new IllegalStateException("Query did not reuse the completed background generation");
                    warmTimes.add(elapsed); queryTimes.add(query);
                    var observation = observations.addObject().put("save_to_ready_ms", elapsed)
                            .put("save_to_analysis_start_ms", (completed.started - lastSave) / 1_000_000.0)
                            .put("analysis_roundtrip_ms", (completed.finished - completed.started) / 1_000_000.0)
                            .put("first_save_to_ready_ms", (completed.finished - firstSave) / 1_000_000.0)
                            .put("query_ms", query).put("observed_background_attempts", attempts)
                            .put("analysis_generation", current.path("analysis_generation").asLong());
                    observation.set("background_stages_ms", completed.result.path("analysis_timings_ms"));
                    System.err.println("sources=" + sources.size() + " saves=" + saves + " round=" + round
                            + " ready_ms=" + Math.round(elapsed) + " query_ms=" + Math.round(query));
                }
                mode.put("save_to_ready_median_ms", percentile(warmTimes, .5)).put("save_to_ready_p95_ms", percentile(warmTimes, .95))
                        .put("query_median_ms", percentile(queryTimes, .5)).put("query_p95_ms", percentile(queryTimes, .95));
            }
            idleStarted = System.nanoTime(); cpuStarted = os.getProcessCpuTime();
            Thread.sleep(2000);
            report.put("steady_idle_observation_ms", (System.nanoTime() - idleStarted) / 1_000_000.0)
                    .put("steady_idle_parent_cpu_ms", (os.getProcessCpuTime() - cpuStarted) / 1_000_000.0);
            Files.writeString(Path.of(args[2]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        } finally {
            KotlinWorkerClient.shared().close();
            try (var paths = Files.walk(module)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void verify(JsonNode result, String descriptor, boolean reused) {
        if (!result.path("status").asText().equals("resolved") || !result.path("descriptor").asText().equals(descriptor)
                || result.path("analysis_reused").asBoolean() != reused) throw new IllegalStateException(result.toString());
    }

    private static double percentile(List<Double> values, double quantile) {
        var sorted = values.stream().sorted().toList();
        if (quantile == .5 && sorted.size() % 2 == 0) return (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1));
    }
}
