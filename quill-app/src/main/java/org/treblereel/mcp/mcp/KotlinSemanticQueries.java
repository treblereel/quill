package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexReader;

/** K2 analysis in a bounded JVM child process; runtime preparation belongs to init. */
final class KotlinSemanticQueries {
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode resolve(Jdbi db, Path root, Path file, int offset) {
        if (file.toString().endsWith(".kts")) return status("unavailable");
        Path scratch = null;
        Process process = null;
        try {
            root = root.toAbsolutePath().normalize();
            file = file.toAbsolutePath().normalize();
            Path module = file.getParent();
            while (module != null && module.startsWith(root) && !Files.isRegularFile(module.resolve("pom.xml"))
                    && !BuildSystem.hasGradleMarker(module)) module = module.getParent();
            if (module == null || !module.startsWith(root)) return status("module_unknown");
            boolean test = module.relativize(file).toString().replace('\\', '/').startsWith("src/test/");
            BuildSystem system = BuildSystem.detect(module);
            Path captured = test ? system.testClasspathFile(module) : system.classpathFile(module);
            if (!Files.isRegularFile(captured)) return status("classpath_missing");
            var request = JSON.createObjectNode();
            request.put("file", file.toString()); request.put("offset", offset);
            var sources = request.putArray("sources"); var javaRoots = request.putArray("java_roots");
            LinkedHashSet<Path> kotlinFiles = new LinkedHashSet<>();
            for (String set : test ? List.of("main", "test") : List.of("main")) {
                Path source = module.resolve("src/" + set);
                if (!Files.isDirectory(source)) continue;
                if (Files.isDirectory(source.resolve("java"))) javaRoots.add(source.resolve("java").toString());
                try (var paths = Files.walk(source)) {
                    for (Path path : paths.filter(value -> value.toString().endsWith(".kt") && Files.isRegularFile(value)).toList()) {
                        if (!path.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
                        kotlinFiles.add(path.toAbsolutePath().normalize());
                        if (kotlinFiles.size() > 5000) return status("source_limit");
                    }
                }
            }
            kotlinFiles.add(file); kotlinFiles.forEach(value -> sources.add(value.toString()));
            var classpath = request.putArray("classpath");
            var entries = new LinkedHashSet<String>();
            for (String entry : Files.readString(captured).strip().split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path path = module.resolve(entry).normalize();
                if (!Files.exists(path)) return status("classpath_missing");
                entries.add(path.toAbsolutePath().toString());
            }
            String relative = root.relativize(module).toString().replace('\\', '/');
            String applicationModule = relative.isEmpty() ? "." : relative;
            var modules = new LinkedHashSet<String>(); modules.add(applicationModule);
            IndexReader.findModuleClasspath(db, applicationModule).forEach(value -> modules.add(value.visibleModule()));
            for (String visible : modules) {
                Path directory = root.resolve(visible).normalize();
                if (!directory.startsWith(root)) return status("module_unknown");
                for (String output : List.of("target/classes", "build/classes/java/main", "build/classes/kotlin/main"))
                    if (Files.isDirectory(directory.resolve(output))) entries.add(directory.resolve(output).toString());
            }
            if (test && Files.isDirectory(module.resolve("target/test-classes"))) entries.add(module.resolve("target/test-classes").toString());
            entries.forEach(classpath::add);
            scratch = Files.createTempDirectory("quill-kotlin-semantic-");
            Path output = scratch.resolve("response.json");
            List<String> worker;
            try { worker = org.treblereel.mcp.kotlin.KotlinRuntime.workerCommand(); }
            catch (java.io.IOException missing) { return status("runtime_missing"); }
            process = new ProcessBuilder(worker).redirectOutput(output.toFile())
                    .redirectError(scratch.resolve("stderr.txt").toFile()).start();
            try (var input = process.getOutputStream()) { JSON.writeValue(input, request); }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); return status("timeout");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) > 1024 * 1024) return status("worker_failed");
            JsonNode response = JSON.readTree(output.toFile());
            if (response == null || !response.isObject() || !response.path("status").isTextual()
                    || !response.path("backend").asText().equals("kotlin_k2_fir")) return status("worker_failed");
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return status("interrupted");
        } catch (Exception failure) { return status(process == null ? "unavailable" : "worker_failed"); }
        finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (scratch != null) try (var paths = Files.walk(scratch)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (Exception ignored) { /* Temporary files are never project files. */ }
        }
    }

    private static JsonNode status(String status) { return JSON.createObjectNode().put("status", status); }
}
