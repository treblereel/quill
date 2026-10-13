package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Obtains the evaluated Gradle project structure without embedding Gradle in Quill. */
public final class GradleProjectDiscovery {

    private static final String CACHE_VERSION = "7";

    public record Discovery(
            List<Path> moduleDirectories,
            List<Path> classesDirectories,
            Map<Path, Path> classDirectoryOwners,
            Map<Path, String> classDirectorySourceSets,
            Map<Path, String> projectPaths,
            List<Path> sourceRoots,
            boolean complete) {
        public Discovery(List<Path> moduleDirectories, List<Path> classesDirectories,
                Map<Path, Path> classDirectoryOwners, Map<Path, String> classDirectorySourceSets,
                Map<Path, String> projectPaths, boolean complete) {
            this(moduleDirectories, classesDirectories, classDirectoryOwners,
                    classDirectorySourceSets, projectPaths, List.of(), complete);
        }

        public Discovery {
            moduleDirectories = List.copyOf(moduleDirectories);
            classesDirectories = List.copyOf(classesDirectories);
            classDirectoryOwners = Map.copyOf(classDirectoryOwners);
            classDirectorySourceSets = Map.copyOf(classDirectorySourceSets);
            projectPaths = Map.copyOf(projectPaths);
            sourceRoots = List.copyOf(sourceRoots);
        }
    }

    private record ParsedManifest(
            List<Path> moduleDirectories,
            List<Path> classesDirectories,
            Map<Path, Path> classDirectoryOwners,
            Map<Path, String> classDirectorySourceSets,
            Map<Path, String> projectPaths,
            List<Path> sourceRoots,
            boolean valid) {}

    private GradleProjectDiscovery() {}

    /**
     * Asks the target build's Gradle wrapper to export JVM source sets, class
     * directories, and runtime classpaths. The target build is evaluated normally,
     * so dynamic settings and custom project directories are respected.
     */
    public static Discovery discover(Path projectRoot) {
        return discover(projectRoot, false);
    }

    /** Discovers projects and refreshes each compiled module's runtime classpath cache. */
    public static Discovery discoverAndWriteClasspath(Path projectRoot) {
        return discover(projectRoot, true);
    }

    static Discovery discover(Path projectRoot, boolean writeClasspath) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!writeClasspath) {
            Discovery cached = readCache(normalizedRoot);
            if (cached != null) return cached;
        }

        Path initScript = null;
        Path manifest = null;
        Process process = null;
        try {
            initScript = Files.createTempFile("quill-gradle-", ".init.gradle");
            manifest = Files.createTempFile("quill-gradle-projects-", ".tsv");
            Files.deleteIfExists(manifest);

            String taskName = "quillDiscover" + UUID.randomUUID().toString().replace("-", "");
            Files.writeString(
                    initScript, initScript(taskName, writeClasspath), StandardCharsets.UTF_8);

            process = new ProcessBuilder(BuildSystem.GRADLE.command(normalizedRoot,
                    "--init-script", initScript.toString(),
                    // Export tasks capture evaluated Project objects and listeners. Keep
                    // them outside the project's configuration cache for this invocation.
                    "--no-configuration-cache",
                    "-Dquill.internal=true",
                    "-Dquill.manifest=" + manifest,
                    "-Dquill.project=" + normalizedRoot,
                    ":" + taskName, "--quiet"))
                    .directory(normalizedRoot.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            int exit = process.waitFor();
            if (exit != 0) {
                System.err.println("[quill] Warning: Gradle project discovery exited with code " + exit);
                return incomplete();
            }

            ParsedManifest parsed = parseManifest(manifest);
            if (!parsed.valid()) {
                System.err.println("[quill] Warning: Gradle project discovery returned an invalid manifest");
            } else if (writeClasspath) {
                String buildFingerprint = DependencyIndexer.buildFingerprint(
                        normalizedRoot, BuildSystem.GRADLE, parsed.moduleDirectories());
                writeCache(normalizedRoot, manifest, buildFingerprint);
            }
            return new Discovery(
                    parsed.moduleDirectories(), parsed.classesDirectories(),
                    parsed.classDirectoryOwners(), parsed.classDirectorySourceSets(),
                    parsed.projectPaths(), parsed.sourceRoots(),
                    parsed.valid());
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            System.err.println("[quill] Warning: Gradle project discovery was interrupted");
            return incomplete();
        } catch (IOException | RuntimeException e) {
            System.err.println("[quill] Warning: could not discover Gradle projects: " + e.getMessage());
            return incomplete();
        } finally {
            deleteTemporaryFile(initScript);
            deleteTemporaryFile(manifest);
        }
    }

    private static void terminate(Process process) {
        if (process == null) return;
        try (var descendants = process.descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Best effort; destroying the wrapper process is still preferable to leaving it alive.
        }
        process.destroyForcibly();
    }

    static Discovery readManifest(Path manifest) {
        ParsedManifest parsed = parseManifest(manifest);
        return new Discovery(parsed.moduleDirectories(), parsed.classesDirectories(),
                parsed.classDirectoryOwners(), parsed.classDirectorySourceSets(),
                parsed.projectPaths(), parsed.sourceRoots(),
                parsed.valid());
    }

    private static ParsedManifest parseManifest(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return new ParsedManifest(List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of(), false);
        }

        Set<Path> modules = new LinkedHashSet<>();
        Set<Path> classes = new LinkedHashSet<>();
        Map<Path, Path> owners = new LinkedHashMap<>();
        Map<Path, String> sourceSets = new LinkedHashMap<>();
        Map<Path, String> projectPaths = new LinkedHashMap<>();
        Set<Path> roots = new LinkedHashSet<>();
        boolean valid = true;
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\\t", -1);
                try {
                    if (fields[0].equals("@sources")) {
                        if (fields.length < 2) throw new IllegalArgumentException("Missing source roots");
                        for (int i = 1; i < fields.length; i++) roots.add(decodePath(fields[i]));
                        continue;
                    }
                    Path module = decodePath(fields[0]);
                    modules.add(module);
                    if (fields.length < 3 || !fields[1].startsWith(":")
                            || !validSourceSet(fields[2])) {
                        valid = false;
                        continue;
                    }
                    projectPaths.putIfAbsent(module, fields[1]);
                    String sourceSet = fields[2];
                    for (int i = 3; i < fields.length; i++) {
                        if (!fields[i].isEmpty()) {
                            Path classesDirectory = decodePath(fields[i]);
                            classes.add(classesDirectory);
                            Path previousOwner = owners.putIfAbsent(classesDirectory, module);
                            String previousSet = sourceSets.putIfAbsent(classesDirectory, sourceSet);
                            if (previousOwner != null && (!previousOwner.equals(module)
                                    || !previousSet.equals(sourceSet))) valid = false;
                        }
                    }
                } catch (IllegalArgumentException e) {
                    valid = false;
                }
            }
        } catch (IOException e) {
            return new ParsedManifest(List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of(), false);
        }
        return new ParsedManifest(
                new ArrayList<>(modules), new ArrayList<>(classes), owners, sourceSets,
                projectPaths, new ArrayList<>(roots), valid);
    }

    public static boolean validSourceSet(String name) {
        return name != null && name.matches("[A-Za-z0-9_][A-Za-z0-9._-]*")
                && !name.equals(".") && !name.equals("..");
    }

    private static Path decodePath(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("Empty Gradle project path");
        byte[] decoded = Base64.getDecoder().decode(value);
        return Path.of(new String(decoded, StandardCharsets.UTF_8)).toAbsolutePath().normalize();
    }

    private static Discovery incomplete() {
        return new Discovery(List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of(), false);
    }

    private static Discovery readCache(Path projectRoot) {
        Path manifest = cacheManifest(projectRoot);
        Path fingerprint = cacheFingerprint(projectRoot);
        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(fingerprint)) return null;
        try {
            Discovery cached = readManifest(manifest);
            if (!cached.complete()) return null;
            String buildFingerprint = DependencyIndexer.buildFingerprint(
                    projectRoot, BuildSystem.GRADLE, cached.moduleDirectories());
            if (!Files.readString(fingerprint, StandardCharsets.UTF_8).trim()
                    .equals(cacheKey(buildFingerprint))) {
                return null;
            }
            return cached;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(Path projectRoot, Path source, String buildFingerprint) {
        Path manifest = cacheManifest(projectRoot);
        try {
            Files.createDirectories(manifest.getParent());
            Files.copy(source, manifest, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(cacheFingerprint(projectRoot), cacheKey(buildFingerprint),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not cache Gradle project discovery: "
                    + e.getMessage());
        }
    }

    private static String cacheKey(String buildFingerprint) {
        return CACHE_VERSION + ":" + buildFingerprint;
    }

    private static Path cacheManifest(Path projectRoot) {
        return projectRoot.resolve("build/quill-projects.tsv");
    }

    private static Path cacheFingerprint(Path projectRoot) {
        return projectRoot.resolve("build/quill-projects.sha256");
    }

    private static void deleteTemporaryFile(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Temporary OS files are safe to leave for later cleanup.
        }
    }

    private static String initScript(String taskName, boolean writeClasspath) {
        return (writeClasspath ? KotlinGradleCapture.script() + JavaGradleCapture.script() : "") + """
                gradle.rootProject { root ->
                    root.tasks.register('%s') {
                        doLast {
                            def requestedInput = new File(System.getProperty('quill.project')).absoluteFile
                            def requested = requestedInput.canonicalFile
                            def manifest = new File(System.getProperty('quill.manifest'))
                            def encoder = Base64.encoder
                            def rootRequested = root.projectDir.canonicalFile == requested
                            def records = []

                            root.allprojects.sort { it.path }.each { project ->
                                def projectDir = project.projectDir.canonicalFile
                                def selected = rootRequested || projectDir.toPath().startsWith(requested.toPath())
                                def sourceSets = selected ? project.extensions.findByName('sourceSets') : null
                                def selectedSourceSets = sourceSets == null ? [] : sourceSets.toList()
                                    .sort { a, b ->
                                        def rank = { name -> name == 'main' ? 0 : name == 'test' ? 1 : 2 }
                                        rank(a.name) <=> rank(b.name) ?: a.name <=> b.name
                                    }.collect { [name: it.name, value: it] }
                                if (!selectedSourceSets.isEmpty()) {
                                    // Resolve Kotlin plugin configurations while a Gradle task
                                    // owns the project lock, before buildFinished tears it down.
                                    if (%s) {
                                        quillCaptureKotlin(project)
                                        project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile).toList().each { task -> quillCaptureJava(project, task, false) }
                                    }
                                    def displayPath = { file ->
                                        def canonical = file.canonicalFile.toPath()
                                        if (canonical.startsWith(requested.toPath())) {
                                            return requestedInput.toPath()
                                                .resolve(requested.toPath().relativize(canonical)).toFile()
                                        }
                                        return file.canonicalFile
                                    }
                                    selectedSourceSets.each { selectedSourceSet ->
                                        def classDirs = selectedSourceSet.value.output.classesDirs.files
                                            .collect { it.canonicalFile }.toSet()
                                        def javaTask = project.tasks.findByName(selectedSourceSet.value.compileJavaTaskName)
                                        if (javaTask instanceof org.gradle.api.tasks.compile.JavaCompile) {
                                            // A task-level destination override need not update SourceSetOutput.
                                            def configured = selectedSourceSet.value.java.destinationDirectory.orNull
                                            if (configured != null) classDirs.remove(configured.asFile.canonicalFile)
                                            def actual = javaTask.destinationDirectory.orNull
                                            if (actual != null) classDirs.add(actual.asFile.canonicalFile)
                                        }
                                        classDirs = classDirs.sort { it.absolutePath }
                                        selectedSourceSet.classDirs = classDirs
                                        def fields = [encoder.encodeToString(
                                                displayPath(projectDir).absolutePath.getBytes('UTF-8')),
                                            project.path, selectedSourceSet.name]
                                        fields.addAll(classDirs.collect { file ->
                                            encoder.encodeToString(
                                                displayPath(file).absolutePath.getBytes('UTF-8'))
                                        })
                                        records << fields.join('\t')
                                        def roots = selectedSourceSet.value.allSource.srcDirs
                                            .collect { encoder.encodeToString(displayPath(it).absolutePath.getBytes('UTF-8')) }
                                            .unique().sort()
                                        if (!roots.isEmpty()) records << (['@sources'] + roots).join('\t')
                                    }

                                    def main = sourceSets.findByName('main')
                                    def compiledSourceSets = selectedSourceSets.findAll { it.classDirs.any { it.isDirectory() } }
                                    if (%s && !compiledSourceSets.isEmpty()) {
                                        def buildDir = new File(projectDir, 'build')
                                        def classpath = new File(buildDir, 'quill-classpath.txt')
                                        classpath.parentFile.mkdirs()
                                        // The dependency index covers all compiled sets; semantic workers use task-specific captures.
                                        new File(buildDir, 'quill-source-set-classpath.txt').setText(compiledSourceSets.collectMany { it.value.runtimeClasspath.files.toList() }
                                            .findAll { it.isFile() && it.name.endsWith('.jar') }
                                            .collect { it.canonicalPath }.unique().sort()
                                            .join(File.pathSeparator), 'UTF-8')
                                        classpath.setText((main == null ? [] : main.runtimeClasspath.files)
                                            .findAll { it.isFile() && it.name.endsWith('.jar') }
                                            .collect { it.canonicalPath }.unique().sort().join(File.pathSeparator), 'UTF-8')
                                        def test = sourceSets.findByName('test')
                                        if (test != null && test.output.classesDirs.files.any {
                                                it.isDirectory() }) {
                                            new File(buildDir, 'quill-test-classpath.txt')
                                                .setText(test.runtimeClasspath.files
                                                    .findAll { it.isFile()
                                                        && it.name.endsWith('.jar') }
                                                    .collect { it.canonicalPath }.sort()
                                                    .join(File.pathSeparator), 'UTF-8')
                                        }
                                        new File(buildDir, 'quill-classpath.sha256').delete()
                                        def directDependencies = project.configurations
                                            .collectMany { configuration ->
                                                configuration.dependencies.collect { dependency ->
                                                    [dependency.group, dependency.name, configuration.name]
                                                }
                                            }
                                            .findAll { it[0] != null && it[1] != null }
                                            .unique { it[0] + ':' + it[1] }
                                            .sort { left, right ->
                                                (left[0] + ':' + left[1]) <=> (right[0] + ':' + right[1])
                                            }
                                        new File(buildDir, 'quill-direct-dependencies.tsv')
                                            .setText(directDependencies.collect {
                                                it.collect { field -> field.toString().replace('\\t', ' ') }
                                                    .join('\\t')
                                            }.join(System.lineSeparator()), 'UTF-8')
                                    }
                                }
                            }
                            manifest.parentFile.mkdirs()
                            manifest.setText(records.join(System.lineSeparator()), 'UTF-8')
                        }
                    }
                }
                """.formatted(taskName, writeClasspath, writeClasspath);
    }
}
