package org.treblereel.mcp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Build-time jars of the Quarkus extensions on a project's classpath: each extension's declared
 * deployment artifact and that artifact's direct compile dependencies in the same group family,
 * found next to the runtime jars in the local Maven repository. Agents ask for deployment
 * processors to see framework behaviour; these jars are never on the application classpath.
 */
final class QuarkusDeploymentArtifacts {

    record Jar(Path path, String coordinates) {
    }

    private static final int MAX_JARS = 300;
    private static final Map<String, List<Jar>> CACHE = new ConcurrentHashMap<>();

    private QuarkusDeploymentArtifacts() {
    }

    /** The build-time jars for these runtime jars; cached per classpath fingerprint. */
    static List<Jar> jars(String fingerprint, List<Path> runtimeJars) {
        return CACHE.computeIfAbsent(fingerprint, ignored -> {
            if (CACHE.size() > 8) CACHE.clear();
            return resolve(runtimeJars);
        });
    }

    private static List<Jar> resolve(List<Path> runtimeJars) {
        Set<Path> seen = new LinkedHashSet<>(runtimeJars);
        List<Jar> result = new ArrayList<>();
        for (Path runtime : runtimeJars) {
            if (result.size() >= MAX_JARS) break;
            String deployment = deploymentArtifact(runtime);
            if (deployment == null) continue;
            String[] gav = deployment.split(":");
            if (gav.length != 3) continue;
            Path jar = locate(runtime, gav[0], gav[1], gav[2]);
            if (jar == null || !seen.add(jar)) continue;
            result.add(new Jar(jar, deployment));
            for (String[] dependency : dependencies(jar, gav[0], gav[2])) {
                Path found = locate(runtime, dependency[0], dependency[1], dependency[2]);
                if (found != null && seen.add(found)) result.add(new Jar(found, String.join(":", dependency)));
            }
        }
        return List.copyOf(result);
    }

    private static String deploymentArtifact(Path runtime) {
        try (JarFile jar = new JarFile(runtime.toFile())) {
            var entry = jar.getJarEntry("META-INF/quarkus-extension.properties");
            if (entry == null) return null;
            Properties properties = new Properties();
            try (InputStream input = jar.getInputStream(entry)) { properties.load(input); }
            return properties.getProperty("deployment-artifact");
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The repository root is the ancestor of a runtime jar under which the coordinates' path exists. */
    private static Path locate(Path runtime, String group, String artifact, String version) {
        String relative = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
        Path root = runtime.getParent();
        for (int depth = 0; root != null && depth < 12; depth++, root = root.getParent()) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** Direct non-test, non-optional dependencies in the same group family; an unstated version is the artifact's. */
    private static List<String[]> dependencies(Path jar, String group, String version) {
        Path pom = jar.resolveSibling(jar.getFileName().toString().replaceAll("\\.jar$", ".pom"));
        if (!Files.isRegularFile(pom)) return List.of();
        String family = group.contains(".") ? group.substring(0, group.indexOf('.', group.indexOf('.') + 1) < 0
                ? group.length() : group.indexOf('.', group.indexOf('.') + 1)) : group;
        List<String[]> result = new ArrayList<>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(pom.toFile());
            var nodes = document.getElementsByTagName("dependency");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element dependency)) continue;
                // Only the project's own dependencies, not dependencyManagement entries.
                if (dependency.getParentNode() == null || dependency.getParentNode().getParentNode() == null
                        || !"project".equals(dependency.getParentNode().getParentNode().getNodeName())) continue;
                String depGroup = text(dependency, "groupId"), artifact = text(dependency, "artifactId");
                String depVersion = text(dependency, "version"), scope = text(dependency, "scope");
                if (depGroup == null || artifact == null || "true".equals(text(dependency, "optional"))) continue;
                if (scope != null && !scope.equals("compile")) continue;
                if (!depGroup.equals(family) && !depGroup.startsWith(family + ".")) continue;
                result.add(new String[] {depGroup, artifact, depVersion == null || depVersion.contains("${") ? version : depVersion});
            }
        } catch (Exception unreadable) {
            return List.of();
        }
        return result;
    }

    private static String text(Element parent, String name) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element && element.getNodeName().equals(name)) return element.getTextContent().strip();
        return null;
    }
}
