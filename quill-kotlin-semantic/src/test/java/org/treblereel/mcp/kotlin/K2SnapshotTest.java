package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("K2_ANALYZER")
class K2SnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    @BeforeEach void reset() { K2Analyzer.clear(); }
    @AfterEach void dispose() { K2Analyzer.clear(); }
    private Path source(String name, String text) throws Exception {
        Path path = temp.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, text); return path;
    }
    private ObjectNode request(Path file, String token, Path... sources) throws Exception {
        var request = JSON.createObjectNode().put("file", file.toString())
                .put("offset", Files.readString(file).lastIndexOf(token)).put("module", temp.toString()).put("source_set", "main");
        var files = request.putArray("sources"); for (Path path : sources) files.add(path.toString());
        request.putArray("classpath").add(Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return request;
    }
    private JsonNode resolve(ObjectNode request, String descriptor) {
        var result = K2Analyzer.analyze(request);
        assertEquals("resolved", result.path("status").asText(), result.toString());
        assertEquals(descriptor, result.path("descriptor").asText(), result.toString()); return result;
    }
    private void rebuilt(JsonNode first, JsonNode next) {
        assertFalse(next.path("analysis_reused").asBoolean(), next.toString());
        assertTrue(next.path("analysis_generation").asLong() > first.path("analysis_generation").asLong());
    }
    @Test void differentPositionsAndFilesReuseFirRatherThanACachedPositionResult() throws Exception {
        Path defs = source("Defs.kt", "package probe\nfun number() = 1\nfun text() = \"s\"");
        Path a = source("A.kt", "package probe\nfun run() = number()");
        Path b = source("B.kt", "package probe\nfun other() = text()");
        var first = resolve(request(a, "number()", defs, a, b), "()I");
        var next = resolve(request(b, "text()", defs, a, b), "()Ljava/lang/String;");
        assertTrue(next.path("analysis_reused").asBoolean());
        assertEquals(first.path("analysis_generation"), next.path("analysis_generation"));
        var invalid = request(a, "number()", defs, a, b).put("offset", -1);
        var result = K2Analyzer.analyze(invalid);
        assertEquals("unresolved", result.path("status").asText()); assertTrue(result.path("analysis_reused").asBoolean());
    }
    @Test void sameSizeSourceEditWithRestoredMtimeInvalidatesDependentFile() throws Exception {
        Path defs = source("Defs.kt", "package probe\nfun pick() = 1000");
        Path caller = source("Caller.kt", "package probe\nfun run() = pick()");
        var request = request(caller, "pick()", defs, caller);
        var first = resolve(request, "()I");
        var modified = Files.getLastModifiedTime(defs); long size = Files.size(defs);
        Files.writeString(defs, "package probe\nfun pick() = true"); Files.setLastModifiedTime(defs, modified);
        assertEquals(size, Files.size(defs)); assertEquals(modified, Files.getLastModifiedTime(defs));
        rebuilt(first, resolve(request, "()Z"));
        assertTrue(resolve(request, "()Z").path("analysis_reused").asBoolean());
    }
    @Test void additionsDeletionsAndBrokenSourceInvalidateCachedErrorsAndFir() throws Exception {
        Path caller = source("Caller.kt", "package probe\nfun run() = missing()");
        var request = request(caller, "missing()", caller);
        var error = K2Analyzer.analyze(request);
        assertEquals("analysis_error", error.path("status").asText());
        assertTrue(K2Analyzer.analyze(request).path("analysis_reused").asBoolean());
        Path defs = source("Defs.kt", "package probe\nfun missing() = 1");
        request.withArray("sources").add(defs.toString());
        var valid = resolve(request, "()I"); rebuilt(error, valid);
        Files.delete(defs); request.withArray("sources").remove(1);
        var deleted = K2Analyzer.analyze(request);
        assertEquals("analysis_error", deleted.path("status").asText()); rebuilt(valid, deleted);
        Files.writeString(caller, "package probe\nfun run() = 1"); request.put("offset", 0);
        var repaired = K2Analyzer.analyze(request); rebuilt(deleted, repaired);
        assertEquals("unresolved", repaired.path("status").asText());
    }
    @Test void javaSourceEditsAdditionsAndDeletionsInvalidateSnapshot() throws Exception {
        Path caller = source("Caller.kt", "fun run() = lib.Library.pick()");
        Path java = source("java/lib/Library.java", "package lib; public class Library { public static int pick() { return 1; } }");
        var request = request(caller, "pick()", caller); request.putArray("java_roots").add(temp.resolve("java").toString());
        var first = resolve(request, "()I"); var modified = Files.getLastModifiedTime(java);
        Files.writeString(java, "package lib; public class Library { public static boolean pick() { return true; } }");
        Files.setLastModifiedTime(java, modified); var next = resolve(request, "()Z"); rebuilt(first, next);
        Files.delete(java);
        var deleted = K2Analyzer.analyze(request); rebuilt(next, deleted); assertEquals("analysis_error", deleted.path("status").asText());
        Files.writeString(java, "package lib; public class Library { public static int pick() { return 1; } }");
        rebuilt(deleted, resolve(request, "()I"));
    }
    private Path compileLibrary(String type, String value, String directory) throws Exception {
        Path java = source("binary-source/Library.java", "package lib; public class Library { public static " + type + " pick() { return " + value + "; } }");
        Path output = temp.resolve(directory); Files.createDirectories(output);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", output.toString(), java.toString())); return output;
    }
    @Test void rebuiltClassWithSameSizeAndRestoredMtimeInvalidatesFir() throws Exception {
        Path output = compileLibrary("int", "1", "classes");
        Path caller = source("Caller.kt", "fun run() = lib.Library.pick()");
        var request = request(caller, "pick()", caller); request.withArray("classpath").add(output.toString());
        var first = resolve(request, "()I"); Path binary = output.resolve("lib/Library.class");
        var modified = Files.getLastModifiedTime(binary); long size = Files.size(binary);
        compileLibrary("boolean", "true", "classes"); Files.setLastModifiedTime(binary, modified);
        assertEquals(size, Files.size(binary)); rebuilt(first, resolve(request, "()Z"));
        Files.delete(binary); var deleted = K2Analyzer.analyze(request);
        assertEquals("analysis_error", deleted.path("status").asText());
    }
    private void jar(Path output, Path jar) throws Exception {
        try (var zip = new JarOutputStream(Files.newOutputStream(jar))) {
            byte[] bytes = Files.readAllBytes(output.resolve("lib/Library.class"));
            var entry = new JarEntry("lib/Library.class"); entry.setTime(0); entry.setMethod(ZipEntry.STORED);
            var crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue()); entry.setSize(bytes.length);
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
        }
    }
    @Test void replacedJarWithSameSizeAndRestoredMtimeDoesNotUseOldBinarySymbols() throws Exception {
        Path output = compileLibrary("int", "1", "classes"); Path jar = temp.resolve("dependency.jar"); jar(output, jar);
        Path caller = source("Caller.kt", "fun run() = lib.Library.pick()");
        var request = request(caller, "pick()", caller); request.withArray("classpath").add(jar.toString());
        var first = resolve(request, "()I"); var modified = Files.getLastModifiedTime(jar); long size = Files.size(jar);
        compileLibrary("boolean", "true", "classes"); jar(output, jar); Files.setLastModifiedTime(jar, modified);
        assertEquals(size, Files.size(jar)); rebuilt(first, resolve(request, "()Z"));
        Files.delete(jar); assertThrows(java.io.IOException.class, () -> K2Analyzer.analyze(request));
        jar(output, jar); assertFalse(resolve(request, "()Z").path("analysis_reused").asBoolean());
    }
    @Test void classpathOrderModuleAndSourceSetArePartOfSnapshotIdentity() throws Exception {
        Path ints = compileLibrary("int", "1", "int-classes"); Path bools = compileLibrary("boolean", "true", "bool-classes");
        Path caller = source("Caller.kt", "fun run() = lib.Library.pick()");
        var request = request(caller, "pick()", caller);
        String stdlib = request.path("classpath").get(0).asText();
        request.putArray("classpath").add(stdlib).add(ints.toString()).add(bools.toString());
        var first = resolve(request, "()I");
        request.putArray("classpath").add(stdlib).add(bools.toString()).add(ints.toString());
        var reversed = resolve(request, "()Z"); rebuilt(first, reversed);
        request.put("source_set", "test"); var test = resolve(request, "()Z"); rebuilt(reversed, test);
        Files.createDirectories(temp.resolve("other"));
        request.put("module", temp.resolve("other").toString()); var other = resolve(request, "()Z"); rebuilt(test, other);
        request.put("module", temp.toString()); rebuilt(other, resolve(request, "()Z"));
    }
    @Test void unchangedBytesAfterTouchReuseSnapshotAndRacingInputsSuppressAttestation() throws Exception {
        Path caller = source("Caller.kt", "fun pick() = 1\nfun run() = pick()");
        var request = request(caller, "pick()", caller); var first = resolve(request, "()I");
        Files.setLastModifiedTime(caller, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));
        assertTrue(resolve(request, "()I").path("analysis_reused").asBoolean());
        var changing = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance) {
            int reads;
            @Override public JsonNode path(String field) {
                if (field.equals("source_set")) return com.fasterxml.jackson.databind.node.TextNode.valueOf(++reads == 1 ? "main" : "test");
                return super.path(field);
            }
        };
        changing.setAll(request);
        changing.put("profile_analysis", true);
        var raced = K2Analyzer.analyze(changing);
        assertEquals("inputs_changed", raced.path("status").asText());
        timings(raced, "capture_before", "capture_after", "dispose", "total");
        rebuilt(first, resolve(request, "()I"));
    }
    @Test void concurrentQueriesSerializeAndShareOnlyOneSnapshot() throws Exception {
        Path caller = source("Caller.kt", "fun pick() = 1\nfun run() = pick()");
        var request = request(caller, "pick()", caller); var first = resolve(request, "()I");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 12; i++) tasks.add(pool.submit(() -> K2Analyzer.analyze(request)));
            for (var task : tasks) {
                var result = task.get(10, TimeUnit.SECONDS);
                assertTrue(result.path("analysis_reused").asBoolean());
                assertEquals(first.path("analysis_generation"), result.path("analysis_generation"));
            }
        } finally { pool.shutdownNow(); }
        K2Analyzer.clear(); assertFalse(resolve(request, "()I").path("analysis_reused").asBoolean());
    }
    private void timings(JsonNode result, String... stages) {
        var timings = result.path("analysis_timings_ms");
        assertTrue(timings.isObject(), result.toString());
        double sum = 0;
        for (var values = timings.fields(); values.hasNext();) {
            var stage = values.next();
            assertTrue(stage.getValue().isNumber());
            assertTrue(Double.isFinite(stage.getValue().asDouble()));
            assertTrue(stage.getValue().asDouble() >= 0);
            if (!stage.getKey().equals("total")) sum += stage.getValue().asDouble();
        }
        assertTrue(timings.path("total").asDouble() >= sum, timings.toString());
        for (String stage : stages) assertTrue(timings.has(stage), timings.toString());
    }
    @Test void profilingIsOptInAndDoesNotInvalidateOrCachePositionTimings() throws Exception {
        Path caller = source("Caller.kt", "fun pick() = 1\nfun run() = pick()");
        var request = request(caller, "pick()", caller).put("profile_analysis", true);
        var cold = resolve(request, "()I");
        timings(cold, "capture_before", "dispose", "configuration", "frontend", "lookup", "capture_after", "total");
        var warm = resolve(request, "()I");
        timings(warm, "capture_before", "lookup", "capture_after", "total");
        assertFalse(warm.path("analysis_timings_ms").has("frontend"));
        assertFalse(warm.path("analysis_timings_ms").has("configuration"));
        assertTrue(warm.path("analysis_reused").asBoolean());
        assertEquals(cold.path("analysis_generation"), warm.path("analysis_generation"));
        request.put("profile_analysis", false);
        var ordinary = resolve(request, "()I");
        assertFalse(ordinary.has("analysis_timings_ms")); assertTrue(ordinary.path("analysis_reused").asBoolean());
        request.remove("profile_analysis");
        assertFalse(resolve(request, "()I").has("analysis_timings_ms"));
        request.put("profile_analysis", true).put("offset", -1);
        var unresolved = K2Analyzer.analyze(request);
        assertEquals("unresolved", unresolved.path("status").asText());
        timings(unresolved, "lookup", "total");
    }
    @Test void cachedErrorsReportOnlyWorkPerformedInCurrentProfiledRequest() throws Exception {
        Path caller = source("Caller.kt", "fun run() = missing()");
        var request = request(caller, "missing()", caller).put("profile_analysis", true);
        var first = K2Analyzer.analyze(request);
        assertEquals("analysis_error", first.path("status").asText());
        timings(first, "configuration", "frontend", "capture_before", "capture_after", "total");
        var cached = K2Analyzer.analyze(request);
        assertEquals("analysis_error", cached.path("status").asText());
        assertTrue(cached.path("analysis_reused").asBoolean());
        timings(cached, "capture_before", "capture_after", "total");
        assertFalse(cached.path("analysis_timings_ms").has("frontend"));
        assertFalse(cached.path("analysis_timings_ms").has("lookup"));
        Files.writeString(caller, "fun missing() = 1\nfun run() = missing()");
        request.put("offset", Files.readString(caller).lastIndexOf("missing()"));
        var repaired = resolve(request, "()I"); rebuilt(cached, repaired);
        timings(repaired, "configuration", "frontend", "lookup", "total");
    }
}
