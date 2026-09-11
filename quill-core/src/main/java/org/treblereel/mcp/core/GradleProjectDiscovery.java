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

    private static final String CACHE_VERSION = "2";

    public record Discovery(
            List<Path> moduleDirectories,
            List<Path> classesDirectories,
            Map<Path, Path> classDirectoryOwners,
            boolean complete) {
        public Discovery {
            moduleDirectories = List.copyOf(moduleDirectories);
            classesDirectories = List.copyOf(classesDirectories);
            classDirectoryOwners = Map.copyOf(classDirectoryOwners);
        }
    }

    private record ParsedManifest(
            List<Path> moduleDirectories,
            List<Path> classesDirectories,
            Map<Path, Path> classDirectoryOwners,
            boolean valid) {}

    private GradleProjectDiscovery() {}

    /**
     * Asks the target build's Gradle wrapper to export Java projects, main class
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
        try {
            initScript = Files.createTempFile("quill-gradle-", ".init.gradle");
            manifest = Files.createTempFile("quill-gradle-projects-", ".tsv");
            Files.deleteIfExists(manifest);

            String taskName = "quillDiscover" + UUID.randomUUID().toString().replace("-", "");
            Files.writeString(
                    initScript, initScript(taskName, writeClasspath), StandardCharsets.UTF_8);

            int exit = new ProcessBuilder(BuildSystem.GRADLE.command(normalizedRoot,
                    "--init-script", initScript.toString(),
                    "-Dquill.manifest=" + manifest,
                    "-Dquill.project=" + normalizedRoot,
                    ":" + taskName, "--quiet"))
                    .directory(normalizedRoot.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor();
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
                    parsed.classDirectoryOwners(), parsed.valid());
        } catch (InterruptedException e) {
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

    static Discovery readManifest(Path manifest) {
        ParsedManifest parsed = parseManifest(manifest);
        return new Discovery(parsed.moduleDirectories(), parsed.classesDirectories(),
                parsed.classDirectoryOwners(), parsed.valid());
    }

    private static ParsedManifest parseManifest(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return new ParsedManifest(List.of(), List.of(), Map.of(), false);
        }

        Set<Path> modules = new LinkedHashSet<>();
        Set<Path> classes = new LinkedHashSet<>();
        Map<Path, Path> owners = new LinkedHashMap<>();
        boolean valid = true;
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\\t", -1);
                try {
                    Path module = decodePath(fields[0]);
                    modules.add(module);
                    for (int i = 1; i < fields.length; i++) {
                        if (!fields[i].isEmpty()) {
                            Path classesDirectory = decodePath(fields[i]);
                            classes.add(classesDirectory);
                            owners.putIfAbsent(classesDirectory, module);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    valid = false;
                }
            }
        } catch (IOException e) {
            return new ParsedManifest(List.of(), List.of(), Map.of(), false);
        }
        return new ParsedManifest(
                new ArrayList<>(modules), new ArrayList<>(classes), owners, valid);
    }

    private static Path decodePath(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("Empty Gradle project path");
        byte[] decoded = Base64.getDecoder().decode(value);
        return Path.of(new String(decoded, StandardCharsets.UTF_8)).toAbsolutePath().normalize();
    }

    private static Discovery incomplete() {
        return new Discovery(List.of(), List.of(), Map.of(), false);
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
        return """
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
                                def main = sourceSets?.findByName('main')
                                if (main != null) {
                                    def classDirs = main.output.classesDirs.files
                                            .collect { it.canonicalFile }
                                            .sort { it.absolutePath }
                                    def displayPath = { file ->
                                        def canonical = file.canonicalFile.toPath()
                                        if (canonical.startsWith(requested.toPath())) {
                                            return requestedInput.toPath()
                                                .resolve(requested.toPath().relativize(canonical)).toFile()
                                        }
                                        return file.canonicalFile
                                    }
                                    def fields = [displayPath(projectDir)] + classDirs.collect(displayPath)
                                    records << fields.collect {
                                        encoder.encodeToString(it.absolutePath.getBytes('UTF-8'))
                                    }.join('\t')

                                    if (%s && classDirs.any { it.isDirectory() }) {
                                        def buildDir = new File(projectDir, 'build')
                                        def classpath = new File(buildDir, 'quill-classpath.txt')
                                        classpath.parentFile.mkdirs()
                                        classpath.setText(main.runtimeClasspath.files
                                            .findAll { it.isFile() && it.name.endsWith('.jar') }
                                            .collect { it.canonicalPath }.sort()
                                            .join(File.pathSeparator), 'UTF-8')
                                        new File(buildDir, 'quill-classpath.sha256').delete()
                                    }
                                }
                            }
                            manifest.parentFile.mkdirs()
                            manifest.setText(records.join(System.lineSeparator()), 'UTF-8')
                        }
                    }
                }
                """.formatted(taskName, writeClasspath);
    }
}
