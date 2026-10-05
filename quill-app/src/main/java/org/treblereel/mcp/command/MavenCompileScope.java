package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.treblereel.mcp.core.MavenProjectDiscovery;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Conservative static closure for Maven's -am, including test/provided and build dependencies. */
public final class MavenCompileScope {
    public record Scope(List<String> modules, boolean repository, String reason) {
        public Scope { modules = List.copyOf(modules); }
    }

    private MavenCompileScope() {}

    public static Scope resolve(Path root, Set<String> requested) {
        root = root.toAbsolutePath().normalize();
        if (requested.isEmpty() || requested.contains(".")) return whole("repository_command");
        var discovery = MavenProjectDiscovery.discover(root);
        if (!discovery.complete()) return whole("incomplete_reactor");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Map<Path, Document> models = new LinkedHashMap<>();
            Map<String, Set<Path>> artifacts = new LinkedHashMap<>();
            for (Path module : discovery.moduleDirectories()) {
                if (!module.startsWith(root)) return whole("external_reactor_module");
                Document model = factory.newDocumentBuilder().parse(module.resolve("pom.xml").toFile());
                models.put(module, model);
                String artifact = child(model.getDocumentElement(), "artifactId");
                if (artifact == null || artifact.contains("${")) return whole("unresolved_artifact");
                artifacts.computeIfAbsent(artifact, ignored -> new LinkedHashSet<>()).add(module);
            }
            Set<Path> selected = new LinkedHashSet<>();
            ArrayDeque<Path> queue = new ArrayDeque<>();
            for (String module : requested) {
                Path path = root.resolve(module).normalize();
                if (!models.containsKey(path)) return whole("unknown_module");
                queue.add(path);
            }
            while (!queue.isEmpty()) {
                Path module = queue.remove();
                if (!selected.add(module)) continue;
                Document model = models.get(module);
                // Include local parents for inherited dependencies, plugins and build inputs.
                var parents = model.getElementsByTagName("parent");
                for (int i = 0; i < parents.getLength(); i++) {
                    Element parent = (Element) parents.item(i);
                    String relative = child(parent, "relativePath");
                    if (relative != null && relative.isBlank()) return whole("external_parent");
                    Path parentPath = module.resolve(relative == null ? "../pom.xml" : relative).normalize();
                    Document parentModel = models.get(parentPath.getParent());
                    if (parentModel == null || !parentPath.getFileName().toString().equals("pom.xml")) {
                        return whole("external_parent");
                    }
                    for (String coordinate : List.of("groupId", "artifactId", "version")) {
                        String expected = child(parent, coordinate);
                        String actual = child(parentModel.getDocumentElement(), coordinate);
                        if (actual == null && !coordinate.equals("artifactId")) {
                            var inherited = parentModel.getElementsByTagName("parent");
                            if (inherited.getLength() > 0) actual = child((Element) inherited.item(0), coordinate);
                        }
                        if (expected == null || expected.contains("${") || !expected.equals(actual)) {
                            return whole("unresolved_parent");
                        }
                    }
                }
                // Artifact-only matching deliberately over-approximates group/version differences.
                // Scan dependencyManagement, profiles, plugins and annotation-processor config too;
                // compile/runtime classpath edges alone omit test/provided/build prerequisites.
                var references = model.getElementsByTagName("artifactId");
                for (int i = 0; i < references.getLength(); i++) {
                    String artifact = references.item(i).getTextContent().strip();
                    if (artifact.contains("${")) return whole("unresolved_artifact_reference");
                    queue.addAll(artifacts.getOrDefault(artifact, Set.of()));
                }
            }
            Path base = root;
            return new Scope(selected.stream().map(path -> path.equals(base) ? "."
                    : base.relativize(path).toString().replace('\\', '/')).sorted().toList(),
                    false, "static_maven_prerequisites");
        } catch (Exception ignored) {
            return whole("unreadable_maven_model");
        }
    }

    private static String child(Element element, String name) {
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && child.getTagName().equals(name)) {
                return child.getTextContent().strip();
            }
        }
        return null;
    }

    private static Scope whole(String reason) { return new Scope(List.of(), true, reason); }
}
