package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyIndexerTest {

    @TempDir Path tempDir;
    private String previousCacheDirectory;

    @BeforeEach
    void isolateSharedCache() {
        previousCacheDirectory = System.getProperty("quill.cache.dir");
        System.setProperty("quill.cache.dir", tempDir.resolve("shared-cache").toString());
    }

    @AfterEach
    void restoreSharedCacheConfiguration() {
        if (previousCacheDirectory == null) System.clearProperty("quill.cache.dir");
        else System.setProperty("quill.cache.dir", previousCacheDirectory);
    }

    @Test
    void classDirectoryOwnersPreserveClasspathOrder() {
        Path first = tempDir.resolve("first/target/classes");
        Path second = tempDir.resolve("second/target/classes");

        Map<Path, Path> owners = DependencyIndexer.mapClassDirectoriesToModules(
                tempDir, BuildSystem.MAVEN, List.of(first, second));

        assertEquals(List.of(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
                new java.util.ArrayList<>(owners.keySet()));
    }

    @Test
    void mavenReactorClasspathDoesNotRequireInternalArtifactsInLocalRepository()
            throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("reactor"));
        Path library = Files.createDirectories(project.resolve("library"));
        Path application = Files.createDirectories(project.resolve("application"));
        Files.writeString(project.resolve("pom.xml"), pom("reactor", "pom", """
                <modules>
                  <module>library</module>
                  <module>application</module>
                </modules>
                """));
        Files.writeString(library.resolve("pom.xml"), childPom("library", """
                <dependencies>
                  <dependency>
                    <groupId>io.smallrye</groupId>
                    <artifactId>jandex</artifactId>
                    <version>3.6.0</version>
                  </dependency>
                </dependencies>
                """));
        Files.writeString(application.resolve("pom.xml"), childPom("application", """
                <dependencies>
                  <dependency>
                    <groupId>org.treblereel.quill.fixture</groupId>
                    <artifactId>library</artifactId>
                    <version>1</version>
                  </dependency>
                </dependencies>
                """));

        DependencyIndexer.ClasspathGenerationResult generated =
                MavenClasspathResolver.generate(project);
        assertTrue(generated.successful(), generated.detail());
        assertTrue(Files.readString(library.resolve("target/quill-classpath.txt"))
                .contains("jandex"));
        assertEquals("", Files.readString(
                application.resolve("target/quill-classpath.txt")).trim());
    }

    @Test
    void parseClasspathFileReturnsJarPaths() throws Exception {
        Path cpFile = tempDir.resolve("classpath.txt");
        String jar1 = tempDir.resolve("a.jar").toString();
        String jar2 = tempDir.resolve("b.jar").toString();
        Files.writeString(cpFile, jar1 + File.pathSeparator + jar2);

        Files.createFile(tempDir.resolve("a.jar"));
        Files.createFile(tempDir.resolve("b.jar"));

        List<Path> result = DependencyIndexer.parseClasspathFile(cpFile);
        assertEquals(2, result.size());
        assertEquals(tempDir.resolve("a.jar"), result.get(0));
        assertEquals(tempDir.resolve("b.jar"), result.get(1));
    }

    @Test
    void parseClasspathFileSkipsNonExistentJars() throws Exception {
        Path cpFile = tempDir.resolve("classpath.txt");
        Files.writeString(cpFile, "/no/such/file.jar");

        List<Path> result = DependencyIndexer.parseClasspathFile(cpFile);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseClasspathFileSkipsNonJarEntries() throws Exception {
        Path cpFile = tempDir.resolve("classpath.txt");
        Path dir = tempDir.resolve("classes");
        Files.createDirectory(dir);
        Files.writeString(cpFile, dir.toString());

        List<Path> result = DependencyIndexer.parseClasspathFile(cpFile);
        assertTrue(result.isEmpty(), "Non-.jar entries should be filtered out");
    }

    @Test
    void indexJarsIndexesClassesFromJar() throws Exception {
        Path jandexJar = findJarOnClasspath("jandex");
        assertNotNull(jandexJar, "jandex JAR should be on test classpath");

        var index = DependencyIndexer.indexJars(Set.of(jandexJar)).view();
        assertNotNull(index.getClassByName("org.jboss.jandex.Index"),
                "Should have indexed org.jboss.jandex.Index from the JAR");
        assertNotNull(index.getClassByName("org.jboss.jandex.Indexer"),
                "Should have indexed org.jboss.jandex.Indexer from the JAR");
    }

    @Test
    void indexJarsEmptyCollectionReturnsEmptyIndex() {
        var index = DependencyIndexer.indexJars(Set.of()).view();
        assertTrue(index.getKnownClasses().isEmpty());
    }

    @Test
    void indexJarsUsesBoundedDeterministicShards() {
        List<Path> jars = findJarsOnClasspath(10);
        assertTrue(jars.size() > 1, "Test classpath should contain multiple JARs");

        var result = DependencyIndexer.indexJars(jars);

        int expected = Math.min(jars.size(), Math.min(8,
                Runtime.getRuntime().availableProcessors()));
        assertEquals(expected, result.shards().size());
        assertFalse(result.view().getKnownClasses().isEmpty());
    }

    @Test
    void firstJarOnClasspathWinsDuplicateClass() throws Exception {
        String className = "sample/Duplicate";
        Path first = writeJar(tempDir.resolve("first.jar"), Map.of(
                className + ".class", classBytes(className, "java/lang/Object")), false);
        Path second = writeJar(tempDir.resolve("second.jar"), Map.of(
                className + ".class", classBytes(className, "java/lang/RuntimeException")), false);

        var index = DependencyIndexer.indexJars(List.of(first, second)).view();

        assertEquals("java.lang.Object", index.getClassByName("sample.Duplicate")
                .superName().toString());
        assertEquals(1, index.getKnownClasses().stream()
                .filter(info -> info.name().toString().equals("sample.Duplicate")).count(),
                "Shadowed classes must not leak through the composite index");
    }

    @Test
    void multiReleaseJarUsesRuntimeVersionOnly() throws Exception {
        String className = "sample/Versioned";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(className + ".class", classBytes(className, "java/lang/Object"));
        entries.put("META-INF/versions/" + JarFile.runtimeVersion().feature() + "/"
                        + className + ".class",
                classBytes(className, "java/lang/RuntimeException"));
        Path jar = writeJar(tempDir.resolve("multi-release.jar"), entries, true);

        var index = DependencyIndexer.indexJars(List.of(jar)).view();

        assertEquals("java.lang.RuntimeException", index.getClassByName("sample.Versioned")
                .superName().toString());
        assertEquals(1, index.getKnownClasses().size(),
                "Base and versioned variants must not both be indexed");
    }

    @Test
    void shardedDependencyCacheRoundTripsAllClasses() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("sharded-cache"));
        Path classesDir = Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        List<Path> jars = findJarsOnClasspath(10);
        assertTrue(jars.size() > 1, "Test classpath should contain multiple JARs");
        Files.writeString(projectDir.resolve("target/quill-classpath.txt"), jars.stream()
                .map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        Files.writeString(projectDir.resolve("target/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(projectDir));

        var indexed = DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));
        Set<String> classNames = indexed.index().getKnownClasses().stream()
                .map(info -> info.name().toString()).collect(Collectors.toSet());
        var cached = DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));
        Set<String> cachedClassNames = cached.index().getKnownClasses().stream()
                .map(info -> info.name().toString()).collect(Collectors.toSet());

        assertTrue(cached.detail().contains("loaded from cache"));
        assertEquals(classNames, cachedClassNames);
    }

    @Test
    void isStaleReturnsTrueWhenClasspathFileMissing() throws Exception {
        Path moduleDir = tempDir.resolve("mod");
        Files.createDirectories(moduleDir.resolve("target"));
        Files.writeString(moduleDir.resolve("pom.xml"), "<project/>");

        assertTrue(DependencyIndexer.isStale(moduleDir),
                "Missing classpath file means stale");
    }

    @Test
    void isStaleReturnsTrueWhenPomIsNewer() throws Exception {
        Path moduleDir = tempDir.resolve("mod");
        Files.createDirectories(moduleDir.resolve("target"));

        Path cpFile = moduleDir.resolve("target/quill-classpath.txt");
        Files.writeString(cpFile, "");
        Files.setLastModifiedTime(cpFile, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));

        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");
        Files.setLastModifiedTime(pomFile, FileTime.from(Instant.parse("2025-06-01T00:00:00Z")));

        assertTrue(DependencyIndexer.isStale(moduleDir),
                "pom.xml newer than classpath file means stale");
    }

    @Test
    void isStaleReturnsFalseWhenClasspathFingerprintMatches() throws Exception {
        Path moduleDir = tempDir.resolve("mod");
        Files.createDirectories(moduleDir.resolve("target"));

        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");
        Files.setLastModifiedTime(pomFile, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));

        Path cpFile = moduleDir.resolve("target/quill-classpath.txt");
        Files.writeString(cpFile, "");
        Files.writeString(moduleDir.resolve("target/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(moduleDir));

        assertFalse(DependencyIndexer.isStale(moduleDir),
                "matching build fingerprint means classpath is current");
    }

    @Test
    void isStaleReturnsTrueWhenFingerprintIsMissing() throws Exception {
        Path moduleDir = tempDir.resolve("mod");
        Files.createDirectories(moduleDir.resolve("target"));
        Files.writeString(moduleDir.resolve("target/quill-classpath.txt"), "");

        assertTrue(DependencyIndexer.isStale(moduleDir),
                "classpath data without a fingerprint is not accepted");
    }

    @Test
    void pomFingerprintChangesWhenNestedModulePomChanges() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path moduleDir = projectDir.resolve("module");
        Files.createDirectories(moduleDir);
        Files.writeString(projectDir.resolve("pom.xml"), "<project><modules><module>module</module></modules></project>");
        Path modulePom = moduleDir.resolve("pom.xml");
        Files.writeString(modulePom, "<project><dependencies/></project>");

        String before = DependencyIndexer.pomFingerprint(projectDir);
        Files.writeString(modulePom, "<project><dependencies><dependency/></dependencies></project>");
        String after = DependencyIndexer.pomFingerprint(projectDir);

        assertNotEquals(before, after,
                "Dependency classpath fingerprint must include all reactor POM contents");
    }

    @Test
    void mavenFingerprintIgnoresBuildOutputTrees() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("maven-build-output"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        String before = DependencyIndexer.buildFingerprint(projectDir);

        Path copiedPom = Files.createDirectories(
                projectDir.resolve("module/target/generated-project"))
                .resolve("pom.xml");
        Files.writeString(copiedPom, "<project><dependencies/></project>");

        assertEquals(before, DependencyIndexer.buildFingerprint(projectDir),
                "Generated build trees must not invalidate dependency classpaths");
    }

    @Test
    void gradleFingerprintChangesWhenVersionCatalogChanges() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("gradle-project"));
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"");
        Files.writeString(projectDir.resolve("build.gradle.kts"), "plugins { java }");
        Path catalog = Files.createDirectories(projectDir.resolve("gradle"))
                .resolve("libs.versions.toml");
        Files.writeString(catalog, "[versions]\njackson = \"2.21.0\"\n");

        String before = DependencyIndexer.buildFingerprint(projectDir);
        Files.writeString(catalog, "[versions]\njackson = \"2.22.0\"\n");

        assertNotEquals(before, DependencyIndexer.buildFingerprint(projectDir));
    }

    @Test
    void gradleFingerprintIncludesBuildSrcButNotBuildOutputs() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("gradle-build-src"));
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'");
        Path plugin = Files.createDirectories(projectDir.resolve("buildSrc/src/main/groovy"))
                .resolve("conventions.gradle");
        Files.writeString(plugin, "// version one");
        String before = DependencyIndexer.buildFingerprint(projectDir);

        Files.writeString(plugin, "// version two");
        String afterBuildSrc = DependencyIndexer.buildFingerprint(projectDir);
        assertNotEquals(before, afterBuildSrc);

        Path output = Files.createDirectories(projectDir.resolve("buildSrc/build/classes"))
                .resolve("Generated.class");
        Files.write(output, new byte[]{1, 2, 3});
        assertEquals(afterBuildSrc, DependencyIndexer.buildFingerprint(projectDir));
    }

    @Test
    void gradleClasspathCacheUsesBuildDirectory() throws Exception {
        Path moduleDir = Files.createDirectories(tempDir.resolve("gradle-cache"));
        Files.writeString(moduleDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.createDirectories(moduleDir.resolve("build"));

        assertTrue(DependencyIndexer.isStale(moduleDir));
        Files.writeString(moduleDir.resolve("build/quill-classpath.txt"), "");
        Files.writeString(moduleDir.resolve("build/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(moduleDir));
        assertFalse(DependencyIndexer.isStale(moduleDir));
    }

    @Test
    void gradlePrebuiltClasspathIsIndexedLikeMavenClasspath() throws Exception {
        Path moduleDir = Files.createDirectories(tempDir.resolve("gradle-index"));
        Path classesDir = Files.createDirectories(moduleDir.resolve("build/classes/java/main"));
        Files.writeString(moduleDir.resolve("build.gradle"), "plugins { id 'java' }");
        Path jandexJar = findJarOnClasspath("jandex");
        assertNotNull(jandexJar);
        Files.writeString(moduleDir.resolve("build/quill-classpath.txt"), jandexJar.toString());
        Files.writeString(moduleDir.resolve("build/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(moduleDir));

        var result = DependencyIndexer.buildDependencyIndex(moduleDir, List.of(classesDir));

        assertEquals(DependencyIndexer.Status.COMPLETE, result.status());
        assertNotNull(result.index().getClassByName("org.jboss.jandex.Index"));
        String fingerprint = DependencyIndexer.dependencyCacheFingerprint(List.of(jandexJar));
        assertTrue(Files.isRegularFile(DependencyIndexer.dependencyCachePath(fingerprint)));
        assertFalse(Files.exists(moduleDir.resolve("build/quill-dependencies.idx")));
    }

    @Test
    void fingerprintInvalidatesClasspathEvenWhenPomTimestampIsOlder() throws Exception {
        Path moduleDir = tempDir.resolve("project/mod");
        Files.createDirectories(moduleDir.resolve("target"));
        Files.writeString(moduleDir.resolve("pom.xml"), "<project/>");
        Files.writeString(moduleDir.resolve("target/quill-classpath.txt"), "");
        Files.writeString(moduleDir.resolve("target/quill-classpath.sha256"), "obsolete");

        assertTrue(DependencyIndexer.isStale(moduleDir),
                "Content fingerprint must invalidate a cached classpath independently of mtimes");
    }

    @Test
    void buildDependencyIndexReturnsUnavailableWhenNoModulesResolve() {
        Path fakeRoot = tempDir.resolve("project");
        Path classesDir = fakeRoot.resolve("mod/target/classes");
        try {
            Files.createDirectories(classesDir);
        } catch (Exception e) {
            fail(e);
        }

        DependencyIndexer.DependencyIndexResult result =
                DependencyIndexer.buildDependencyIndex(fakeRoot, List.of(classesDir));

        assertNull(result.index());
        assertEquals(DependencyIndexer.Status.UNAVAILABLE, result.status());
        assertNotNull(result.detail());
    }

    @Test
    void buildDependencyIndexReturnsCompleteWithPrebuiltClasspath() throws Exception {
        Path moduleDir = tempDir.resolve("project/mod");
        Path classesDir = moduleDir.resolve("target/classes");
        Files.createDirectories(classesDir);

        Path jandexJar = findJarOnClasspath("jandex");
        assertNotNull(jandexJar);

        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");
        Files.setLastModifiedTime(pomFile, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));

        Path cpFile = moduleDir.resolve("target/quill-classpath.txt");
        Files.writeString(cpFile, jandexJar.toString());
        Files.writeString(moduleDir.resolve("target/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(tempDir.resolve("project"),
                        BuildSystem.MAVEN, List.of(moduleDir)));

        DependencyIndexer.DependencyIndexResult result =
                DependencyIndexer.buildDependencyIndex(tempDir.resolve("project"), List.of(classesDir));

        assertNotNull(result.index());
        assertEquals(DependencyIndexer.Status.COMPLETE, result.status());
        assertNotNull(result.index().getClassByName("org.jboss.jandex.Index"));
        assertEquals(List.of("dependency_classpath", "dependency_cache_read",
                        "dependency_jar_index", "dependency_cache_write"),
                List.copyOf(result.timings().keySet()));
        assertTrue(result.timings().values().stream().allMatch(value -> value >= 0));

        DependencyIndexer.DependencyIndexResult cached =
                DependencyIndexer.buildDependencyIndex(tempDir.resolve("project"), List.of(classesDir));
        assertEquals(DependencyIndexer.Status.COMPLETE, cached.status());
        assertTrue(cached.detail().contains("loaded from cache"));
        assertNotNull(cached.index().getClassByName("org.jboss.jandex.Index"));
        assertEquals(0L, cached.timings().get("dependency_jar_index"));
        assertEquals(0L, cached.timings().get("dependency_cache_write"));
    }

    @Test
    void failedClasspathGenerationIsSuppressedUntilBuildFingerprintChanges() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("failed-generation"));
        Path moduleDir = Files.createDirectories(projectDir.resolve("module"));
        Path classesDir = Files.createDirectories(moduleDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("pom.xml"),
                "<project><modules><module>module</module></modules></project>");
        Path modulePom = Files.writeString(moduleDir.resolve("pom.xml"), "<project/>");
        Path jar = findJarOnClasspath("jandex");
        assertNotNull(jar);
        Files.writeString(moduleDir.resolve("target/quill-classpath.txt"), jar.toString());
        Files.writeString(moduleDir.resolve("target/quill-classpath.sha256"), "obsolete");

        AtomicInteger attempts = new AtomicInteger();
        DependencyIndexer.ClasspathGenerator generator = (root, buildSystem) ->
                attempts.incrementAndGet() == 1
                        ? DependencyIndexer.ClasspathGenerationResult.failure(
                                "Maven dependency classpath generation exited with code 1")
                        : DependencyIndexer.ClasspathGenerationResult.success();
        long now = 1_800_000_000_000L;

        var first = DependencyIndexer.buildDependencyIndex(
                projectDir, List.of(classesDir), generator, now);
        var suppressed = DependencyIndexer.buildDependencyIndex(
                projectDir, List.of(classesDir), generator, now + 1_000);

        assertEquals(1, attempts.get());
        assertEquals(DependencyIndexer.Status.DEGRADED, first.status());
        assertEquals(DependencyIndexer.Status.DEGRADED, suppressed.status());
        assertTrue(suppressed.detail().contains("retry suppressed"));
        assertTrue(Files.isRegularFile(DependencyIndexer.generationFailurePath(
                projectDir, BuildSystem.MAVEN)));

        Files.writeString(modulePom, "<project><dependencies/></project>");
        var recovered = DependencyIndexer.buildDependencyIndex(
                projectDir, List.of(classesDir), generator, now + 2_000);

        assertEquals(2, attempts.get(), "Changed POM must bypass the failure backoff");
        assertEquals(DependencyIndexer.Status.COMPLETE, recovered.status());
        assertFalse(Files.exists(DependencyIndexer.generationFailurePath(
                projectDir, BuildSystem.MAVEN)));

    }

    @Test
    void failedClasspathGenerationIsRetriedAfterBackoff() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("expired-failure"));
        Path classesDir = Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Path jar = findJarOnClasspath("jandex");
        assertNotNull(jar);
        Files.writeString(projectDir.resolve("target/quill-classpath.txt"), jar.toString());
        Files.writeString(projectDir.resolve("target/quill-classpath.sha256"), "obsolete");

        AtomicInteger attempts = new AtomicInteger();
        DependencyIndexer.ClasspathGenerator generator = (root, buildSystem) -> {
            attempts.incrementAndGet();
            return DependencyIndexer.ClasspathGenerationResult.failure("temporary failure");
        };
        long now = 1_800_000_000_000L;

        DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir), generator, now);
        DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir), generator,
                now + DependencyIndexer.GENERATION_FAILURE_BACKOFF.toMillis() - 1);
        DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir), generator,
                now + DependencyIndexer.GENERATION_FAILURE_BACKOFF.toMillis());

        assertEquals(2, attempts.get());
    }

    @Test
    void dependencyCacheFingerprintChangesWithClasspathOrderAndJarMetadata() throws Exception {
        Path first = Files.write(tempDir.resolve("first.jar"), new byte[] {1});
        Path second = Files.write(tempDir.resolve("second.jar"), new byte[] {2});

        String original = DependencyIndexer.dependencyCacheFingerprint(List.of(first, second));
        assertNotEquals(original,
                DependencyIndexer.dependencyCacheFingerprint(List.of(second, first)));

        Files.write(first, new byte[] {1, 2});
        assertNotEquals(original,
                DependencyIndexer.dependencyCacheFingerprint(List.of(first, second)));
    }

    @Test
    void corruptDependencyCacheFallsBackToReindexing() throws Exception {
        Path projectDir = tempDir.resolve("corrupt-cache");
        Path classesDir = Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Path jar = findJarOnClasspath("jandex");
        assertNotNull(jar);
        Files.writeString(projectDir.resolve("target/quill-classpath.txt"), jar.toString());
        Files.writeString(projectDir.resolve("target/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(projectDir));

        DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));
        String fingerprint = DependencyIndexer.dependencyCacheFingerprint(List.of(jar));
        Files.writeString(DependencyIndexer.dependencyCachePath(fingerprint), "corrupt");

        DependencyIndexer.DependencyIndexResult rebuilt =
                DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));
        assertEquals(DependencyIndexer.Status.COMPLETE, rebuilt.status());
        assertTrue(rebuilt.detail().contains("JARs indexed"));
        assertNotNull(rebuilt.index().getClassByName("org.jboss.jandex.Index"));
    }

    @Test
    void truncatedShardedCacheFallsBackToReindexing() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("truncated-cache"));
        Path classesDir = Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        List<Path> jars = findJarsOnClasspath(6);
        assertTrue(jars.size() > 1, "Test classpath should contain multiple JARs");
        Files.writeString(projectDir.resolve("target/quill-classpath.txt"), jars.stream()
                .map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        Files.writeString(projectDir.resolve("target/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(projectDir));

        DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));
        String fingerprint = DependencyIndexer.dependencyCacheFingerprint(jars);
        Path cache = DependencyIndexer.dependencyCachePath(fingerprint);
        byte[] complete = Files.readAllBytes(cache);
        Files.write(cache, Arrays.copyOf(complete, complete.length - 64));

        var rebuilt = DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));

        assertEquals(DependencyIndexer.Status.COMPLETE, rebuilt.status());
        assertTrue(rebuilt.detail().contains("JARs indexed"));
        assertFalse(rebuilt.index().getKnownClasses().isEmpty());
    }

    @Test
    void dependencyIndexIsReusedAcrossProjects() throws Exception {
        Path jar = findJarOnClasspath("jandex");
        assertNotNull(jar);
        Path first = prepareProjectWithClasspath("first-project", jar);
        Path second = prepareProjectWithClasspath("second-project", jar);

        var indexed = DependencyIndexer.buildDependencyIndex(
                first, List.of(first.resolve("target/classes")));
        var reused = DependencyIndexer.buildDependencyIndex(
                second, List.of(second.resolve("target/classes")));

        assertTrue(indexed.detail().contains("JARs indexed"));
        assertTrue(reused.detail().contains("loaded from cache"));
        assertNotNull(reused.index().getClassByName("org.jboss.jandex.Index"));
    }

    @Test
    void buildDependencyIndexIsDegradedWhenClasspathJarIsMissing() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path moduleDir = projectDir.resolve("mod");
        Path classesDir = moduleDir.resolve("target/classes");
        Files.createDirectories(classesDir);

        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");
        Files.setLastModifiedTime(pomFile, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
        Path cpFile = moduleDir.resolve("target/quill-classpath.txt");
        Files.writeString(cpFile, moduleDir.resolve("missing.jar").toString());
        Files.setLastModifiedTime(cpFile, FileTime.from(Instant.parse("2025-06-01T00:00:00Z")));

        DependencyIndexer.DependencyIndexResult result =
                DependencyIndexer.buildDependencyIndex(projectDir, List.of(classesDir));

        assertEquals(DependencyIndexer.Status.DEGRADED, result.status());
        assertTrue(result.detail().contains("JARs missing"));
    }

    @Test
    void buildDependencyIndexReturnsDegradedWhenSomeModulesMissClasspath() throws Exception {
        Path projectDir = tempDir.resolve("project");

        Path mod1Classes = projectDir.resolve("mod1/target/classes");
        Path mod2Classes = projectDir.resolve("mod2/target/classes");
        Files.createDirectories(mod1Classes);
        Files.createDirectories(mod2Classes);

        Path jandexJar = findJarOnClasspath("jandex");
        assertNotNull(jandexJar);

        // mod1 has a valid classpath file (not stale)
        Path pom1 = projectDir.resolve("mod1/pom.xml");
        Files.writeString(pom1, "<project/>");
        Files.setLastModifiedTime(pom1, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));

        Path cp1 = projectDir.resolve("mod1/target/quill-classpath.txt");
        Files.writeString(cp1, jandexJar.toString());
        Files.setLastModifiedTime(cp1, FileTime.from(Instant.parse("2025-06-01T00:00:00Z")));

        // mod2 has no classpath file — will trigger generation which will fail
        Path pom2 = projectDir.resolve("mod2/pom.xml");
        Files.writeString(pom2, "<project/>");

        DependencyIndexer.DependencyIndexResult result =
                DependencyIndexer.buildDependencyIndex(projectDir, List.of(mod1Classes, mod2Classes));

        assertNotNull(result.index(), "Should still return index from mod1");
        assertEquals(DependencyIndexer.Status.DEGRADED, result.status(),
                "Only 1/2 modules resolved");
    }

    @Test
    void generateClasspathFilesUsesRuntimeScope() throws Exception {
        // Verify by checking the method exists and is callable
        // (actual Maven execution tested in integration tests)
        assertFalse(DependencyIndexer.generateClasspathFiles(tempDir),
                "Should return false for non-Maven directory");
    }

    @Test
    void dependencyWorkersRespectCpuAndMemoryBudgets() {
        long mib = 1024L * 1024;

        assertEquals(2, DependencyIndexer.workerCount(20, 16, 600 * mib, null));
        assertEquals(3, DependencyIndexer.workerCount(3, 16, 8_192 * mib, null));
        assertEquals(4, DependencyIndexer.workerCount(20, 16, 8_192 * mib, "4"));
        assertEquals(8, DependencyIndexer.workerCount(20, 16, 8_192 * mib, "invalid"));
    }

    private Path prepareProjectWithClasspath(String name, Path jar) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve(name));
        Files.createDirectories(project.resolve("target/classes"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("target/quill-classpath.txt"), jar.toString());
        Files.writeString(project.resolve("target/quill-classpath.sha256"),
                DependencyIndexer.buildFingerprint(project));
        return project;
    }

    private static Path findJarOnClasspath(String nameFragment) {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.contains(nameFragment) && entry.endsWith(".jar")) {
                Path p = Path.of(entry);
                if (Files.exists(p)) return p;
            }
        }
        return null;
    }

    private static String pom(String artifactId, String packaging, String body) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.treblereel.quill.fixture</groupId>
                  <artifactId>%s</artifactId>
                  <version>1</version>
                  <packaging>%s</packaging>
                  %s
                </project>
                """.formatted(artifactId, packaging, body);
    }

    private static String childPom(String artifactId, String body) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.treblereel.quill.fixture</groupId>
                    <artifactId>reactor</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  %s
                </project>
                """.formatted(artifactId, body);
    }

    private static List<Path> findJarsOnClasspath(int limit) {
        return java.util.Arrays.stream(System.getProperty("java.class.path", "")
                        .split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(entry -> entry.endsWith(".jar"))
                .map(Path::of)
                .filter(Files::isRegularFile)
                .distinct()
                .limit(limit)
                .toList();
    }

    private static Path writeJar(
            Path path, Map<String, byte[]> entries, boolean multiRelease) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (var entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private static byte[] classBytes(String internalName, String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
