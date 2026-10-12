package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.kotlin.KotlinRuntime;

@ResourceLock("SYSTEM_PROPERTIES")
class KotlinSemanticQueriesTest {
    @TempDir Path temp;
    String previousCache, previousOffline;

    @BeforeEach void prepareRuntime() throws Exception {
        previousCache = System.getProperty("quill.kotlin.cache");
        previousOffline = System.getProperty("quill.kotlin.offline");
        System.setProperty("quill.kotlin.cache", temp.resolve("runtime with spaces").toString());
        System.setProperty("quill.kotlin.offline", "true");
        KotlinRuntime.prepare();
    }

    @AfterEach void restoreProperties() {
        KotlinSemanticQueries.closeWarmup();
        org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().close();
        if (previousCache == null) System.clearProperty("quill.kotlin.cache"); else System.setProperty("quill.kotlin.cache", previousCache);
        if (previousOffline == null) System.clearProperty("quill.kotlin.offline"); else System.setProperty("quill.kotlin.offline", previousOffline);
    }

    @Test void missingProjectEvidenceKeepsFallbackWithoutExecutingBuilds() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Path file = temp.resolve("Example.kt"); Files.writeString(file, "fun run() = 1");
        assertEquals("module_unknown", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        assertEquals("classpath_missing", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
        assertFalse(Files.exists(temp.resolve("target")));
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("target/quill-classpath.txt"), "missing-dependency.jar");
        assertEquals("classpath_missing", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
        assertEquals("classpath_missing", KotlinSemanticQueries.resolve(db, temp, temp.resolve("script.kts"), 0).path("status").asText());
    }

    @Test void ordinaryScriptAndProjectSettingsReachWorkerButGradleDslRequiresDefinition() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db")); Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path script = temp.resolve("example.kts"); String text = "fun pick() = 1\nval result = pick()\nerror(\"never run\")";
        Files.writeString(script, text); Files.createDirectories(temp.resolve("target"));
        Path stdlib = Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(temp.resolve("target/quill-classpath.txt"), stdlib.toString());
        Files.writeString(temp.resolve("target/quill-kotlin-main.properties"), "languageVersion=2.3\napiVersion=2.3\njvmTarget=17\n");
        var result = KotlinSemanticQueries.resolve(db, temp, script, text.lastIndexOf("pick()"));
        assertEquals("resolved", result.path("status").asText(), result.toString()); assertEquals("2.3", result.path("language_version").asText());
        assertEquals("script_definition_missing", KotlinSemanticQueries.resolve(db, temp, temp.resolve("build.gradle.kts"), 0).path("status").asText());
    }
    @Test void testSourcesUseTestClasspathAndSeeMainDeclarations() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path main = temp.resolve("src/main/kotlin/Main.kt");
        Path test = temp.resolve("src/test/kotlin/Test.kt");
        Files.createDirectories(main.getParent()); Files.createDirectories(test.getParent());
        Files.writeString(main, "package probe\nfun pick(x: Int) = x");
        String source = "package probe\nfun run() = pick(1)";
        Files.writeString(test, source);
        Files.createDirectories(temp.resolve("target"));
        Path stdlib = Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(temp.resolve("target/quill-test-classpath.txt"), stdlib.toString());
        var result = KotlinSemanticQueries.resolve(db, temp, test, source.lastIndexOf("pick("));
        assertEquals("resolved", result.path("status").asText(), result.toPrettyString());
        assertEquals("probe.MainKt", result.path("owner").asText());
        Files.writeString(test, "package probe\nfun run() = pick(\"bad\")");
        assertEquals("analysis_error", KotlinSemanticQueries.resolve(db, temp, test, 25).path("status").asText());
        assertFalse(Files.exists(temp.resolve("target/test-classes")));
    }

    @Test void preparedK2RunsWithoutChangingOrBuildingTheProject() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Path file = temp.resolve("src/main/kotlin/Example.kt"); Files.createDirectories(file.getParent());
        String source = "package probe\nfun pick(x: Int) = x\nfun run() = pick(1)";
        Files.writeString(file, source); Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Files.createDirectories(temp.resolve("target"));
        Path stdlib = Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(temp.resolve("target/quill-classpath.txt"), stdlib.toString());
        var result = KotlinSemanticQueries.resolve(db, temp, file, source.lastIndexOf("pick("));
        assertEquals("resolved", result.path("status").asText(), result.toPrettyString());
        assertEquals("kotlin_k2_fir", result.path("backend").asText());
        assertEquals("(I)I", result.path("descriptor").asText());
        assertFalse(Files.exists(temp.resolve("target/classes")));
        var timings = new java.util.ArrayList<Double>();
        for (int i = 0; i < 12; i++) {
            String changed = source.replace("pick(1)", i % 2 == 0 ? "pick(2)" : "pick(3)");
            Files.writeString(file, changed);
            long started = System.nanoTime();
            var warm = KotlinSemanticQueries.resolve(db, temp, file, changed.lastIndexOf("pick("));
            timings.add((System.nanoTime() - started) / 1_000_000.0);
            assertEquals("(I)I", warm.path("descriptor").asText(), warm.toString());
        }
        var cachedTimings = new java.util.ArrayList<Double>();
        long generation = -1;
        for (int i = 0; i < 12; i++) {
            long started = System.nanoTime();
            var cached = KotlinSemanticQueries.resolve(db, temp, file, source.lastIndexOf("pick("));
            cachedTimings.add((System.nanoTime() - started) / 1_000_000.0);
            assertTrue(cached.path("analysis_reused").asBoolean(), cached.toString());
            if (generation == -1) generation = cached.path("analysis_generation").asLong();
            assertEquals(generation, cached.path("analysis_generation").asLong());
        }
        cachedTimings.sort(Double::compareTo);
        System.out.println("K2 reused FIR analysis median_ms=" + cachedTimings.get(6));
        timings.sort(Double::compareTo);
        System.out.println("K2 repeated fresh-source analysis median_ms=" + timings.get(6));
        Path other = temp.resolve("another-project");
        Path otherFile = other.resolve("src/main/kotlin/Example.kt");
        Files.createDirectories(otherFile.getParent());
        Files.writeString(other.resolve("pom.xml"), "<project/>");
        Files.createDirectories(other.resolve("target"));
        Files.writeString(other.resolve("target/quill-classpath.txt"), stdlib.toString());
        String otherSource = "package other\nfun pick(x: String) = x\nfun run() = pick(\"s\")";
        Files.writeString(otherFile, otherSource);
        var otherResult = KotlinSemanticQueries.resolve(db, other, otherFile, otherSource.lastIndexOf("pick("));
        assertEquals("other.ExampleKt", otherResult.path("owner").asText(), otherResult.toString());
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", otherResult.path("descriptor").asText());
        assertEquals("probe.ExampleKt", KotlinSemanticQueries.resolve(db, temp, file,
                source.lastIndexOf("pick(")).path("owner").asText());
    }

    @Test void nativeAndJvmUseThePreparedRuntime() throws Exception {
        var command = KotlinRuntime.workerCommand();
        assertEquals("-cp", command.get(2)); assertTrue(command.get(3).contains("runtime with spaces"));
        assertNotEquals(System.getProperty("java.class.path"), command.get(3));
        assertTrue(command.contains("-Xss8m"));
        assertEquals("org.treblereel.mcp.kotlin.SemanticWorker", command.get(command.size() - 1));
    }

    @Test void gradleProjectUsesSameRuntimeAndMissingRuntimeNeverDownloads() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Files.writeString(temp.resolve("build.gradle.kts"), "// Quill must not execute this script");
        Path file = temp.resolve("src/main/kotlin/Example.kt"); Files.createDirectories(file.getParent());
        String source = "package probe\nfun pick(x: Int) = x\nfun run() = pick(1)";
        Files.writeString(file, source);
        Files.createDirectories(temp.resolve("build"));
        Path stdlib = Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(temp.resolve("build/quill-classpath.txt"), stdlib.toString());
        assertEquals("compiler_settings_missing", KotlinSemanticQueries.resolve(db, temp, file, source.lastIndexOf("pick(")).path("status").asText());
        Files.writeString(temp.resolve("build/quill-kotlin-main.properties"), "languageVersion=2.4\n");
        assertEquals("resolved", KotlinSemanticQueries.resolve(db, temp, file, source.lastIndexOf("pick(")).path("status").asText());
        System.setProperty("quill.kotlin.cache", temp.resolve("absent runtime").toString());
        assertEquals("runtime_missing", KotlinSemanticQueries.resolve(db, temp, file, source.lastIndexOf("pick(")).path("status").asText());
        assertFalse(Files.exists(temp.resolve("absent runtime")));
        assertFalse(Files.exists(temp.resolve("build/classes")));
    }

    @Test void filesystemChangeWarmsRealFirBeforeTheNextQuery() throws Exception {
        Path defs = temp.resolve("src/main/kotlin/Definitions.kt");
        Path caller = temp.resolve("src/main/kotlin/Caller.kt"); Files.createDirectories(defs.getParent());
        Files.writeString(defs, "package probe\nfun pick() = 1000");
        String source = "package probe\nfun run() = pick()"; Files.writeString(caller, source);
        var request = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("module", temp.toString()).put("source_set", "main").put("file", caller.toString())
                .put("offset", source.lastIndexOf("pick()"));
        request.putArray("sources").add(caller.toString()).add(defs.toString());
        request.putArray("classpath").add(Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var warmResult = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var warmed = new java.util.concurrent.CountDownLatch(1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            var result = org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().query(r);
            if (r.path("offset").asInt() == -1) { warmResult.set(result); warmed.countDown(); }
            return result;
        }, java.time.Duration.ofMillis(150))) {
            java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> background = () -> request.deepCopy().put("offset", -1);
            var first = coordinator.query("main", () -> request, background);
            assertEquals("()I", first.path("descriptor").asText(), first.toString());
            Files.writeString(defs, "package probe\nfun pick() = true");
            assertTrue(warmed.await(8, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(warmResult.get().path("analysis_reused").asBoolean());
            var next = coordinator.query("main", () -> request, background);
            assertEquals("()Z", next.path("descriptor").asText(), next.toString());
            assertTrue(next.path("analysis_reused").asBoolean(), next.toString());
            assertEquals(warmResult.get().path("analysis_generation"), next.path("analysis_generation"));
        }
    }

    @Test void startupCoordinatorBuildsRealFirBeforeAnyForegroundQuery() throws Exception {
        Path file = temp.resolve("src/main/kotlin/Caller.kt"); Files.createDirectories(file.getParent());
        String source = "package probe\nfun pick() = 1\nfun run() = pick()"; Files.writeString(file, source);
        var request = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("module", temp.toString()).put("source_set", "main").put("file", file.toString());
        request.putArray("sources").add(file.toString()); request.putArray("classpath").add(
                Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var warmed = new java.util.concurrent.CountDownLatch(1);
        var backgroundResult = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            var result = org.treblereel.mcp.kotlin.KotlinWorkerClient.shared().query(r);
            if (r.path("offset").asInt() == -1) { backgroundResult.set(result); warmed.countDown(); }
            return result;
        }, java.time.Duration.ofMillis(150))) {
            java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> background = () -> request.deepCopy().put("offset", -1);
            assertTrue(coordinator.prewarm("main", background));
            assertTrue(warmed.await(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("unresolved", backgroundResult.get().path("status").asText());
            assertFalse(backgroundResult.get().path("analysis_reused").asBoolean());
            var first = coordinator.query("main", () -> request.deepCopy().put("offset", source.lastIndexOf("pick()")), background);
            assertEquals("()I", first.path("descriptor").asText(), first.toString());
            assertTrue(first.path("analysis_reused").asBoolean());
            assertEquals(backgroundResult.get().path("analysis_generation"), first.path("analysis_generation"));
        }
    }

    private org.jdbi.v3.core.Jdbi startupIndex(String buildMarker, String module, String sourceSet) throws Exception {
        var db = QuillDatabase.create(temp.resolve("startup.db"));
        Path directory = module.equals(".") ? temp : temp.resolve(module);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(buildMarker), "// fixture");
        Path file = directory.resolve("src/" + sourceSet + "/kotlin/Caller.kt"); Files.createDirectories(file.getParent());
        Files.writeString(file, "fun pick() = 1\nfun run() = pick()");
        Path captured = directory.resolve(buildMarker.startsWith("pom") ? "target/quill-classpath.txt" : "build/quill-classpath.txt");
        Files.createDirectories(captured.getParent()); Files.writeString(captured,
                Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Files.writeString(captured.getParent().resolve("quill-kotlin-" + sourceSet + ".properties"), "languageVersion=2.4\n");
        addStartupFile(db, temp.relativize(file).toString().replace('\\', '/'), module, sourceSet, "current");
        return db;
    }

    private void addStartupFile(org.jdbi.v3.core.Jdbi db, String path, String module, String set, String lifecycle) {
        db.useHandle(h -> h.execute("INSERT INTO files(project_path,repository_path,kind,origin,lifecycle,module,source_set) VALUES(?,?,'kotlin','source',?,?,?)",
                path, path, lifecycle, module, set));
    }

    @Test void soleGradleMainModuleIsScheduledButSecondStartupDoesNotDisplaceIt() throws Exception {
        var db = startupIndex("build.gradle.kts", ".", "main");
        var projects = List.of(new ProjectRegistry.ProjectEntry("gradle", temp, db));
        assertTrue(KotlinSemanticQueries.prewarm(projects));
        assertFalse(KotlinSemanticQueries.prewarm(projects));
        Path file = temp.resolve("src/main/kotlin/Caller.kt");
        var first = KotlinSemanticQueries.resolve(db, temp, file, Files.readString(file).lastIndexOf("pick()"));
        assertEquals("resolved", first.path("status").asText(), first.toString()); assertEquals("()I", first.path("descriptor").asText());
    }

    @Test void ambiguousModulesAndProjectsAreNotSpeculativelyWarmed() throws Exception {
        var db = startupIndex("pom.xml", ".", "main");
        addStartupFile(db, "other/src/main/kotlin/Other.kt", "other", "main", "current");
        assertFalse(KotlinSemanticQueries.prewarm(List.of(new ProjectRegistry.ProjectEntry("multi", temp, db))));
        db.useHandle(h -> h.execute("DELETE FROM files WHERE module = 'other'"));
        assertFalse(KotlinSemanticQueries.prewarm(List.of(new ProjectRegistry.ProjectEntry("a", temp, db),
                new ProjectRegistry.ProjectEntry("b", temp, db))));
    }

    @Test void testOnlyDeletedAndScriptFilesDoNotSelectAStartupModule() throws Exception {
        var db = startupIndex("pom.xml", ".", "test");
        addStartupFile(db, "src/main/kotlin/Deleted.kt", ".", "main", "deleted");
        addStartupFile(db, "build.gradle.kts", ".", "main", "current");
        assertFalse(KotlinSemanticQueries.prewarm(List.of(new ProjectRegistry.ProjectEntry("ignored", temp, db))));
        assertFalse(KotlinSemanticQueries.prewarm(List.of()));
    }

    @Test void soleMavenSubmoduleIsSelectedAlongsideJavaOnlyModules() throws Exception {
        var db = startupIndex("pom.xml", "app", "main");
        db.useHandle(h -> h.execute("INSERT INTO files(project_path,repository_path,kind,origin,lifecycle,module,source_set) "
                + "VALUES('src/main/java/JavaOnly.java','src/main/java/JavaOnly.java','java','source','current','.','main')"));
        assertTrue(KotlinSemanticQueries.prewarm(List.of(new ProjectRegistry.ProjectEntry("maven", temp, db))));
        Path file = temp.resolve("app/src/main/kotlin/Caller.kt");
        assertEquals("()I", KotlinSemanticQueries.resolve(db, temp, file, Files.readString(file).lastIndexOf("pick()")).path("descriptor").asText());
    }

    @Test void indexedAnchorOutsideProjectCannotSelectAStartupModule() throws Exception {
        var db = startupIndex("pom.xml", ".", "main");
        db.useHandle(h -> h.execute("UPDATE files SET repository_path = '../src/main/kotlin/Caller.kt'"));
        Path restrictedRoot = temp.resolve("project"); Files.createDirectories(restrictedRoot);
        assertFalse(KotlinSemanticQueries.prewarm(List.of(new ProjectRegistry.ProjectEntry("restricted", restrictedRoot, db))));
    }

    @Test void missingRuntimeSourceAndClasspathSkipStartupWithoutDownloading() throws Exception {
        var db = startupIndex("pom.xml", ".", "main");
        var projects = List.of(new ProjectRegistry.ProjectEntry("missing", temp, db));
        System.setProperty("quill.kotlin.cache", temp.resolve("missing runtime").toString());
        assertFalse(KotlinSemanticQueries.prewarm(projects)); assertFalse(Files.exists(temp.resolve("missing runtime")));
        System.setProperty("quill.kotlin.cache", temp.resolve("runtime with spaces").toString());
        Files.delete(temp.resolve("target/quill-classpath.txt")); assertFalse(KotlinSemanticQueries.prewarm(projects));
        Files.delete(temp.resolve("src/main/kotlin/Caller.kt")); assertFalse(KotlinSemanticQueries.prewarm(projects));
    }

    @Test void cancelledStartupCannotWarmAfterLateRegistryResolution() throws Exception {
        var db = startupIndex("pom.xml", ".", "main");
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var registry = new ProjectRegistry() {
            @Override public List<ProjectEntry> initialized() {
                entered.countDown();
                // Model a registry operation which completes after cancellation, despite interruption.
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                finished.countDown(); return List.of(new ProjectEntry("late", temp, db));
            }
        };
        Runnable cancel = KotlinSemanticQueries.startWarmup(registry);
        try {
            assertTrue(entered.await(8, java.util.concurrent.TimeUnit.SECONDS)); cancel.run();
            KotlinSemanticQueries.closeWarmup(); release.countDown();
            assertTrue(finished.await(8, java.util.concurrent.TimeUnit.SECONDS));
            // A direct startup must still be able to select the module after the cancelled task.
            assertTrue(KotlinSemanticQueries.prewarm(List.of(new ProjectRegistry.ProjectEntry("explicit", temp, db))));
        } finally { cancel.run(); release.countDown(); }
    }
}
