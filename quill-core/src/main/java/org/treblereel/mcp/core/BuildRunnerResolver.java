package org.treblereel.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves a usable build runner without assuming a wrapper or PATH installation exists. */
public final class BuildRunnerResolver {

    public record Runner(
            String kind,
            String source,
            boolean available,
            List<String> argvPrefix,
            String unavailableReason) {
        public Runner {
            argvPrefix = List.copyOf(argvPrefix);
        }
    }

    private BuildRunnerResolver() {}

    public static Runner resolve(Path projectRoot, BuildSystem buildSystem) {
        return resolve(projectRoot, buildSystem, BuildSystem.isWindows(),
                System.getenv("PATH"));
    }

    static Runner resolve(Path projectRoot, BuildSystem buildSystem,
            boolean windows, String pathValue) {
        Path root = projectRoot.toAbsolutePath().normalize();
        String unixWrapper = buildSystem == BuildSystem.MAVEN ? "mvnw" : "gradlew";
        String windowsWrapper = buildSystem == BuildSystem.MAVEN ? "mvnw.cmd" : "gradlew.bat";
        String installed = buildSystem == BuildSystem.MAVEN ? "mvn" : "gradle";

        if (windows) {
            Path wrapper = root.resolve(windowsWrapper);
            if (Files.isRegularFile(wrapper)) {
                return new Runner("wrapper", "project", true,
                        List.of("cmd.exe", "/d", "/c", wrapper.toString()), null);
            }
        } else {
            Path wrapper = root.resolve(unixWrapper);
            if (Files.isRegularFile(wrapper) && Files.isExecutable(wrapper)) {
                return new Runner("wrapper", "project", true,
                        List.of(wrapper.toString()), null);
            }
        }

        Path executable = findOnPath(installed, windows, pathValue);
        if (executable != null) {
            List<String> prefix = windows
                    ? List.of("cmd.exe", "/d", "/c", executable.toString())
                    : List.of(executable.toString());
            return new Runner("system", "PATH", true, prefix, null);
        }
        String wrapperIssue = !windows && Files.isRegularFile(root.resolve(unixWrapper))
                ? "Project wrapper exists but is not executable; no system " + installed
                        + " was found on PATH"
                : "No project wrapper or system " + installed + " was found";
        return new Runner("unavailable", "none", false, List.of(), wrapperIssue);
    }

    private static Path findOnPath(String command, boolean windows, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) return null;
        List<String> names = new ArrayList<>();
        if (windows) {
            names.add(command + ".cmd");
            names.add(command + ".bat");
            names.add(command + ".exe");
        } else {
            names.add(command);
        }
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)
                        && (windows || Files.isExecutable(candidate))) return candidate;
            }
        }
        return null;
    }
}
