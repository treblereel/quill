package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectCodeExpectation;

/** Classifies whether main compiled outputs are absent, stale, or current. */
public final class CompiledOutputInspector {

    public enum State {
        FRESH,
        MISSING,
        STALE,
        NOT_APPLICABLE
    }

    public record Report(State state, List<String> staleModules) {
        public Report {
            staleModules = List.copyOf(staleModules);
        }
    }

    private CompiledOutputInspector() {}

    public static Report inspect(Path projectRoot) {
        return inspect(projectRoot, null);
    }

    /** Null means repository scope; otherwise inspect only these root-relative modules. */
    public static Report inspect(Path projectRoot, java.util.Collection<String> modules) {
        Path root = projectRoot.toAbsolutePath().normalize();
        BuildSystem buildSystem = BuildSystem.detect(root);
        if (ProjectCodeExpectation.inspect(root, buildSystem)
                == ProjectCodeExpectation.State.METADATA_ONLY) {
            return new Report(State.NOT_APPLICABLE, List.of());
        }

        ProjectLayout.ClassesDiscovery discovery =
                ProjectLayout.discoverClassesDirs(root, false);
        List<ProjectLayout.CompiledOutput> mainOutputs = discovery.outputs().stream()
                .filter(output -> output.sourceSet().equals("main"))
                .filter(output -> modules == null || modules.contains(moduleName(root, output.moduleDirectory())))
                .toList();
        if (mainOutputs.isEmpty()) return new Report(State.MISSING, List.of());

        Map<Path, List<ProjectLayout.CompiledOutput>> outputsByModule = mainOutputs.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ProjectLayout.CompiledOutput::moduleDirectory,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<String> staleModules = new ArrayList<>();
        long sharedBuildInput = newestSharedBuildInput(root);
        if (modules != null) {
            for (Path module : discovery.moduleDirectories()) {
                if (modules.contains(moduleName(root, module))
                        && newestMainContent(module) == 0
                        && outputsByModule.getOrDefault(module, List.of()).isEmpty()) {
                    sharedBuildInput = Math.max(sharedBuildInput, newestModuleBuildInput(module));
                }
            }
        }
        for (Path module : discovery.moduleDirectories()) {
            if (modules != null && !modules.contains(moduleName(root, module))) continue;
            List<ProjectLayout.CompiledOutput> moduleOutputs =
                    outputsByModule.getOrDefault(module, List.of());
            long newestContent = newestMainContent(module);
            if (newestContent == 0 && moduleOutputs.isEmpty()) continue;
            long newestInput = Math.max(newestContent,
                    Math.max(newestModuleBuildInput(module), sharedBuildInput));
            long newestClass = moduleOutputs.stream()
                    .mapToLong(output -> newestModified(output.directory(), false))
                    .max().orElse(0);
            if (newestClass == 0 || newestInput > newestClass) {
                staleModules.add(moduleName(root, module));
            }
        }
        return staleModules.isEmpty()
                ? new Report(State.FRESH, List.of())
                : new Report(State.STALE, staleModules);
    }

    private static long newestMainContent(Path module) {
        long newest = 0;
        for (Path directory : List.of(
                module.resolve("src/main/java"),
                module.resolve("src/main/kotlin"),
                module.resolve("src/main/resources"))) {
            newest = Math.max(newest, newestModified(directory, true));
        }
        return newest;
    }

    private static long newestModuleBuildInput(Path module) {
        long newest = 0;
        for (Path file : List.of(
                module.resolve("pom.xml"),
                module.resolve("build.gradle"),
                module.resolve("build.gradle.kts"))) {
            newest = Math.max(newest, modified(file));
        }
        return newest;
    }

    private static long newestSharedBuildInput(Path root) {
        long newest = 0;
        for (Path file : List.of(
                root.resolve("pom.xml"),
                root.resolve("settings.gradle"),
                root.resolve("settings.gradle.kts"),
                root.resolve("gradle.properties"),
                root.resolve("gradle/libs.versions.toml"))) {
            newest = Math.max(newest, modified(file));
        }
        return newest;
    }

    private static long newestModified(Path directory, boolean buildInputsOnly) {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> !buildInputsOnly || isBuildInput(path))
                    .mapToLong(CompiledOutputInspector::modified).max().orElse(0);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static boolean isBuildInput(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt")
                || path.toString().contains("src" + java.io.File.separator
                        + "main" + java.io.File.separator + "resources");
    }

    private static long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static String moduleName(Path root, Path module) {
        Path normalized = module.toAbsolutePath().normalize();
        if (normalized.equals(root)) return ".";
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString();
    }
}
