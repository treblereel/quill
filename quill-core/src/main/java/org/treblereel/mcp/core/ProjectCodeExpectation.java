package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Determines whether build metadata declares JVM code that should produce main classes. */
public final class ProjectCodeExpectation {

    public enum State {
        CODE_EXPECTED,
        METADATA_ONLY,
        UNKNOWN
    }

    private ProjectCodeExpectation() {}

    public static State inspect(Path projectRoot, BuildSystem buildSystem) {
        Path root = projectRoot.toAbsolutePath().normalize();
        return buildSystem == BuildSystem.MAVEN ? inspectMaven(root) : inspectGradle(root);
    }

    private static State inspectMaven(Path root) {
        MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);
        if (!discovery.complete() || discovery.moduleDirectories().isEmpty()) return State.UNKNOWN;
        for (Path module : discovery.moduleDirectories()) {
            Model model = readModel(module.resolve("pom.xml"));
            if (model == null) return State.UNKNOWN;
            String packaging = model.getPackaging();
            if (packaging == null || packaging.isBlank()) packaging = "jar";
            if (!"pom".equalsIgnoreCase(packaging.strip())) return State.CODE_EXPECTED;
        }
        return State.METADATA_ONLY;
    }

    private static State inspectGradle(Path root) {
        try {
            if (containsSourceRoot(root)) return State.CODE_EXPECTED;
            boolean settingsOnly = (Files.isRegularFile(root.resolve("settings.gradle"))
                    || Files.isRegularFile(root.resolve("settings.gradle.kts")))
                    && !Files.isRegularFile(root.resolve("build.gradle"))
                    && !Files.isRegularFile(root.resolve("build.gradle.kts"));
            return settingsOnly ? State.METADATA_ONLY : State.UNKNOWN;
        } catch (IOException | RuntimeException failure) {
            return State.UNKNOWN;
        }
    }

    private static boolean containsSourceRoot(Path root) throws IOException {
        try (var paths = Files.find(root, 8, (path, attributes) -> attributes.isDirectory()
                && isMainSourceRoot(path))) {
            return paths.findAny().isPresent();
        }
    }

    private static boolean isMainSourceRoot(Path path) {
        int count = path.getNameCount();
        if (count < 3) return false;
        return "src".equals(path.getName(count - 3).toString())
                && "main".equals(path.getName(count - 2).toString())
                && ("java".equals(path.getName(count - 1).toString())
                || "kotlin".equals(path.getName(count - 1).toString()));
    }

    private static Model readModel(Path pom) {
        if (!Files.isRegularFile(pom)) return null;
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader);
        } catch (IOException | XmlPullParserException | RuntimeException failure) {
            return null;
        }
    }
}
