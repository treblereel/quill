package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Reads the raw Maven reactor structure without embedding Maven itself. */
public final class MavenProjectDiscovery {

    public record Discovery(List<Path> moduleDirectories, boolean complete) {
        public Discovery {
            moduleDirectories = List.copyOf(moduleDirectories);
        }
    }

    private MavenProjectDiscovery() {}

    /**
     * Finds the nearest ancestor reactor that declares {@code projectDirectory}
     * as one of its modules. A standalone project resolves to itself.
     */
    public static Path findReactorRoot(Path projectDirectory) {
        Path project = projectDirectory.toAbsolutePath().normalize();
        Path ancestor = project.getParent();
        while (ancestor != null) {
            if (Files.isRegularFile(ancestor.resolve("pom.xml"))) {
                Discovery discovery = discover(ancestor);
                if (discovery.moduleDirectories().contains(project)) return ancestor;
            }
            ancestor = ancestor.getParent();
        }
        return project;
    }

    /** Returns the reactor root and all recursively declared module directories. */
    public static Discovery discover(Path reactorRoot) {
        Set<Path> modules = new LinkedHashSet<>();
        boolean[] complete = {true};
        visit(reactorRoot.toAbsolutePath().normalize(), modules, complete);
        return new Discovery(new ArrayList<>(modules), complete[0]);
    }

    private static void visit(Path moduleDirectory, Set<Path> modules, boolean[] complete) {
        Path normalized = moduleDirectory.toAbsolutePath().normalize();
        if (!modules.add(normalized)) return;

        Model model = readModel(normalized.resolve("pom.xml"));
        if (model == null) {
            complete[0] = false;
            return;
        }

        for (String module : model.getModules()) {
            Path child = resolveModule(normalized, module, model);
            if (child == null || !Files.isRegularFile(child.resolve("pom.xml"))) {
                complete[0] = false;
                continue;
            }
            visit(child, modules, complete);
        }

        // Raw models cannot evaluate profile activation. Include existing profile
        // modules and later keep only those with compiled main classes.
        for (Profile profile : model.getProfiles()) {
            for (String module : profile.getModules()) {
                Path child = resolveModule(normalized, module, model);
                if (child != null && Files.isRegularFile(child.resolve("pom.xml"))) {
                    visit(child, modules, complete);
                }
            }
        }
    }

    private static Model readModel(Path pom) {
        if (!Files.isRegularFile(pom)) return null;
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader);
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            return null;
        }
    }

    private static Path resolveModule(Path base, String module, Model model) {
        if (module == null || module.isBlank()) return null;
        String resolved = interpolate(module.trim(), base, model.getProperties());
        if (resolved.contains("${")) return null;
        Path path;
        try {
            path = base.resolve(resolved).normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".xml")) {
            return path.getParent();
        }
        return path;
    }

    private static String interpolate(String value, Path base, Properties properties) {
        String result = value
                .replace("${basedir}", base.toString())
                .replace("${project.basedir}", base.toString())
                .replace("${pom.basedir}", base.toString());
        for (int pass = 0; pass < 10 && result.contains("${"); pass++) {
            String previous = result;
            for (String name : properties.stringPropertyNames()) {
                result = result.replace("${" + name + "}", properties.getProperty(name));
            }
            if (result.equals(previous)) break;
        }
        return result;
    }
}
