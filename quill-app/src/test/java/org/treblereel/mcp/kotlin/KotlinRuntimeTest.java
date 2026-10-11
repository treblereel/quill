package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.treblereel.mcp.QuillTopCommand;

@ResourceLock("SYSTEM_PROPERTIES")
class KotlinRuntimeTest {
    @TempDir Path temporary;
    final Map<String, String> previous = new LinkedHashMap<>();
    Path remote;

    void property(String key, String value) {
        if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    @BeforeEach void setup() throws Exception {
        remote = temporary.resolve("repository with spaces"); Files.createDirectories(remote);
        property("quill.kotlin.cache", temporary.resolve("runtime with spaces").toString());
        property("quill.kotlin.localRepository", temporary.resolve("local cache").toString());
        property("quill.kotlin.repository", remote.toUri().toString());
        property("quill.kotlin.settings", temporary.resolve("settings.xml").toString());
        property("quill.kotlin.offline", "false");
        Files.writeString(temporary.resolve("settings.xml"), "<settings/>");
        String child = "<dependencies><dependency><groupId>fixture</groupId><artifactId>child</artifactId>"
                + "<version>1</version><scope>runtime</scope></dependency>"
                + "<dependency><groupId>fixture</groupId><artifactId>absent-test</artifactId>"
                + "<version>1</version><scope>test</scope></dependency>"
                + "<dependency><groupId>fixture</groupId><artifactId>absent-optional</artifactId>"
                + "<version>1</version><optional>true</optional></dependency></dependencies>";
        artifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", KotlinRuntime.version(), child);
        artifact("org.jetbrains.kotlin", "kotlin-stdlib", KotlinRuntime.version(), "");
        artifact("com.fasterxml.jackson.core", "jackson-databind", QuillTopCommand.property("jackson-version"), "");
        artifact("fixture", "child", "1", "");
    }

    @AfterEach void restore() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    @Test void resolvesTransitiveRuntimeOnceAndReusesOfflineCache() throws Exception {
        var files = KotlinRuntime.prepare();
        assertEquals(5, files.size());
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("child-1.jar")));
        property("quill.kotlin.offline", "true");
        assertEquals(files, KotlinRuntime.prepare());
        assertEquals(files, KotlinRuntime.available());
        assertTrue(KotlinRuntime.workerCommand().get(3).contains("runtime with spaces"));
        assertTrue(KotlinRuntime.workerCommand().contains("-Xss8m"));
    }

    private Path isolatedHomeWithoutMavenSettings() throws Exception {
        Path home = temporary.resolve("home with spaces"); Files.createDirectories(home);
        property("user.home", home.toString());
        property("quill.kotlin.settings", null);
        property("quill.kotlin.localRepository", null);
        property("maven.repo.local", null);
        property("quill.kotlin.cache", null);
        assertFalse(Files.exists(home.resolve(".m2")));
        assertTrue(KotlinRuntime.available().isEmpty());
        assertFalse(Files.exists(home.resolve(".quill")));
        return home;
    }

    @Test void absentDefaultMavenDirectoryIsCreatedAndRuntimeReusedOffline() throws Exception {
        Path home = isolatedHomeWithoutMavenSettings();
        var files = KotlinRuntime.prepare();
        assertEquals(5, files.size());
        assertTrue(Files.isRegularFile(home.resolve(".m2/repository/org/jetbrains/kotlin/kotlin-compiler-embeddable/"
                + KotlinRuntime.version() + "/kotlin-compiler-embeddable-" + KotlinRuntime.version() + ".jar")));
        assertFalse(Files.exists(home.resolve(".m2/settings.xml")));
        assertTrue(Files.isRegularFile(KotlinRuntime.directory(KotlinRuntime.cacheRoot()).resolve("classpath.properties")));
        property("quill.kotlin.offline", "true");
        property("quill.kotlin.repository", temporary.resolve("unavailable repository").toUri().toString());
        assertEquals(files, KotlinRuntime.prepare());
        assertEquals(files, KotlinRuntime.available());
    }

    @Test void absentDefaultMavenDirectoryOfflineDoesNotPublishRuntime() throws Exception {
        isolatedHomeWithoutMavenSettings();
        property("quill.kotlin.offline", "true");
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
        assertTrue(KotlinRuntime.available().isEmpty());
        assertFalse(Files.exists(KotlinRuntime.directory(KotlinRuntime.cacheRoot()).resolve("classpath.properties")));
    }

    @Test void customRepositoryDoesNotRequireOrCreateDefaultMavenDirectory() throws Exception {
        Path home = isolatedHomeWithoutMavenSettings();
        Path repository = temporary.resolve("custom local repository");
        property("quill.kotlin.localRepository", repository.toString());
        assertEquals(5, KotlinRuntime.prepare().size());
        assertTrue(Files.isDirectory(repository.resolve("org/jetbrains/kotlin")));
        assertFalse(Files.exists(home.resolve(".m2")));
    }

    @Test void readingMissingRuntimeDoesNotCreateCacheOrResolveAnything() {
        assertTrue(KotlinRuntime.available().isEmpty());
        assertFalse(Files.exists(KotlinRuntime.cacheRoot()));
        assertThrows(java.io.IOException.class, KotlinRuntime::workerCommand);
    }

    @Test void offlineEmptyCacheNeverPublishesPartialRuntime() throws Exception {
        property("quill.kotlin.offline", "true");
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
        assertTrue(KotlinRuntime.available().isEmpty());
        assertFalse(Files.exists(KotlinRuntime.directory(KotlinRuntime.cacheRoot()).resolve("classpath.properties")));
    }

    @Test void concurrentProjectsShareOneCompleteRuntime() throws Exception {
        try (var pool = Executors.newFixedThreadPool(3)) {
            var first = pool.submit(KotlinRuntime::prepare);
            var second = pool.submit(KotlinRuntime::prepare);
            var third = pool.submit(KotlinRuntime::prepare);
            assertEquals(first.get(), second.get()); assertEquals(first.get(), third.get());
            assertEquals(first.get(), KotlinRuntime.available());
        }
    }

    @Test void anotherProcessWaitsForRuntimeLockBeforePublishing() throws Exception {
        Path directory = KotlinRuntime.directory(KotlinRuntime.cacheRoot()); Files.createDirectories(directory);
        Path started = temporary.resolve("child-started");
        var command = new java.util.ArrayList<String>(); command.add(KotlinRuntime.javaExecutable());
        for (String key : previous.keySet()) command.add("-D" + key + "=" + System.getProperty(key));
        command.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"),
                PreparationMain.class.getName(), started.toString()));
        Process process = null;
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("runtime.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(temporary.resolve("child.log").toFile()).start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(started) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(started));
            assertFalse(process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(directory.resolve("classpath.properties")));
            lock.release();
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(temporary.resolve("child.log")));
            assertEquals(5, KotlinRuntime.available().size());
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }

    public static class PreparationMain {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "started");
            KotlinRuntime.prepare();
        }
    }

    @Test void deletedWorkerIsRepairedWithoutNetworkAndChangedJarIsRejected() throws Exception {
        var files = KotlinRuntime.prepare();
        Files.delete(files.getFirst());
        assertTrue(KotlinRuntime.available().isEmpty());
        property("quill.kotlin.offline", "true");
        assertEquals(files, KotlinRuntime.prepare());
        Files.writeString(files.getLast(), "corrupted");
        assertTrue(KotlinRuntime.available().isEmpty());
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
    }

    @Test void missingDependencyAndMalformedOrWrongVersionManifestAreRejected() throws Exception {
        var files = KotlinRuntime.prepare();
        Path manifest = KotlinRuntime.directory(KotlinRuntime.cacheRoot()).resolve("classpath.properties");
        byte[] original = Files.readAllBytes(manifest);
        Files.writeString(manifest, "version=other\ncount=5\n"); assertTrue(KotlinRuntime.available().isEmpty());
        Files.writeString(manifest, "version=" + KotlinRuntime.version() + "\ncount=garbage\n"); assertTrue(KotlinRuntime.available().isEmpty());
        Files.writeString(manifest, "broken=\\uXYZT"); assertTrue(KotlinRuntime.available().isEmpty());
        Files.write(manifest, original);
        Files.delete(files.getLast()); assertTrue(KotlinRuntime.available().isEmpty());
    }

    @Test void checksumFailureDoesNotPublishRuntime() throws Exception {
        Path pom = remote.resolve("fixture/child/1/child-1.pom.sha1"); Files.writeString(pom, "0000000000000000000000000000000000000000");
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
        assertTrue(KotlinRuntime.available().isEmpty());
    }

    @Test void settingsMirrorWorksForAnyProjectBuildSystem() throws Exception {
        property("quill.kotlin.repository", temporary.resolve("nonexistent").toUri().toString());
        Files.writeString(temporary.resolve("settings.xml"), "<settings><mirrors><mirror><id>company</id>"
                + "<url>" + remote.toUri() + "</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>");
        assertEquals(5, KotlinRuntime.prepare().size());
    }

    @Test void httpRepositoryUsesConfiguredServerCredentials() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var authorized = new java.util.concurrent.atomic.AtomicInteger();
        String credential = "Basic " + java.util.Base64.getEncoder().encodeToString("fixture:password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!credential.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fixture\"");
                    exchange.sendResponseHeaders(401, -1); return;
                }
                authorized.incrementAndGet();
                Path file = remote.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if (!file.startsWith(remote) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); return; }
                byte[] bytes = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            property("quill.kotlin.repository", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Files.writeString(temporary.resolve("settings.xml"), "<settings><servers><server><id>central</id>"
                    + "<username>fixture</username><password>password</password></server></servers></settings>");
            assertEquals(5, KotlinRuntime.prepare().size());
            assertTrue(authorized.get() > 0);
        } finally { server.stop(0); }
    }

    @Test void missingOrInvalidSettingsAreReportedWithoutPublishing() throws Exception {
        Files.delete(temporary.resolve("settings.xml"));
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
        Files.writeString(temporary.resolve("settings.xml"), "<settings><broken>");
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
        assertTrue(KotlinRuntime.available().isEmpty());
    }

    @Test void settingsOfflineAndPropertyInterpolationAreRespected() throws Exception {
        Files.writeString(temporary.resolve("settings.xml"), "<settings><offline>true</offline></settings>");
        assertThrows(java.io.IOException.class, KotlinRuntime::prepare);
        assertEquals(System.getProperty("user.home") + "/repo", KotlinRuntime.expand("${user.home}/repo"));
        assertEquals("${missing.quill.property}", KotlinRuntime.expand("${missing.quill.property}"));
    }

    @Test void javaVersionParsingRejectsOldOrUnexpectedLaunchers() {
        assertEquals(17, KotlinRuntime.javaMajor("openjdk version \"17.0.16\" 2025-07-15"));
        assertEquals(25, KotlinRuntime.javaMajor("java version \"25\" 2025-09-16"));
        assertEquals(1, KotlinRuntime.javaMajor("java version \"1.8.0_402\""));
        assertEquals(-1, KotlinRuntime.javaMajor("unknown runtime"));
        assertEquals(-1, KotlinRuntime.javaMajor("openjdk version \"999999999999999999999\""));
    }

    private void artifact(String group, String name, String version, String dependencies) throws Exception {
        Path dir = remote.resolve(group.replace('.', '/') + "/" + name + "/" + version); Files.createDirectories(dir);
        Path pom = dir.resolve(name + "-" + version + ".pom");
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + name + "</artifactId><version>" + version + "</version>" + dependencies + "</project>");
        Path jar = dir.resolve(name + "-" + version + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture.txt")); output.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        for (Path file : java.util.List.of(pom, jar)) Files.writeString(Path.of(file + ".sha1"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file))));
    }
}
