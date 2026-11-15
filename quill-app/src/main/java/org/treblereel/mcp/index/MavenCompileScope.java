package org.treblereel.mcp.index;

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
import org.treblereel.mcp.diagnostics.QuillLog;

/** Conservative static closure for Maven's -am, including test/provided and build dependencies. */
public final class MavenCompileScope {
    public record Scope(List<String> modules, boolean repository, String reason) {
        public Scope { modules = List.copyOf(modules); }
    }

    private MavenCompileScope() {}

    public static Scope resolve(Path root, Set<String> requested) {
        root = root.toAbsolutePath().normalize();
        if (requested.isEmpty() || requested.contains(".")) return whole("repository_command");
        return prepare(root).apply(requested);
    }

    /** Parses the reactor once; the returned closure resolves scopes without filesystem access. */
    public static java.util.function.Function<Set<String>, Scope> prepare(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        var discovery = MavenProjectDiscovery.discover(root);
        if (!discovery.complete()) return requested -> whole("incomplete_reactor");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Map<Path, Document> models = new LinkedHashMap<>();
            Map<String, Set<Path>> artifacts = new LinkedHashMap<>();
            for (Path module : discovery.moduleDirectories()) {
                if (!module.startsWith(root)) return requested -> whole("external_reactor_module");
                Document model = factory.newDocumentBuilder().parse(module.resolve("pom.xml").toFile());
                models.put(module, model);
                String artifact = child(model.getDocumentElement(), "artifactId");
                if (artifact == null || artifact.contains("${")) return requested -> whole("unresolved_artifact");
                artifacts.computeIfAbsent(artifact, ignored -> new LinkedHashSet<>()).add(module);
            }
            Map<String, Set<String>> dependencies = new LinkedHashMap<>();
            Map<String, String> problems = new LinkedHashMap<>();
            for (var entry : models.entrySet()) {
                Path module = entry.getKey();
                Document model = entry.getValue();
                String name = name(root, module);
                String problem = parentProblem(module, model, models);
                Set<String> referenced = new LinkedHashSet<>();
                var references = model.getElementsByTagName("artifactId");
                for (int i = 0; i < references.getLength(); i++) {
                    String artifact = references.item(i).getTextContent().strip();
                    if (artifact.contains("${")) problem = "unresolved_artifact_reference";
                    for (Path target : artifacts.getOrDefault(artifact, Set.of())) referenced.add(name(root, target));
                }
                dependencies.put(name, Set.copyOf(referenced));
                if (problem != null) problems.put(name, problem);
            }
            return requested -> {
                if (requested.isEmpty() || requested.contains(".")) return whole("repository_command");
                Set<String> selected = new LinkedHashSet<>();
                ArrayDeque<String> queue = new ArrayDeque<>(requested);
                while (!queue.isEmpty()) {
                    String module = queue.remove();
                    if (!selected.add(module)) continue;
                    if (!dependencies.containsKey(module)) return whole("unknown_module");
                    if (problems.containsKey(module)) return whole(problems.get(module));
                    queue.addAll(dependencies.get(module));
                }
                return new Scope(selected.stream().sorted().toList(), false, "static_maven_prerequisites");
            };
        } catch (Exception ignored) {
            QuillLog.swallowed(ignored);
            return requested -> whole("unreadable_maven_model");
        }
    }

    private static String name(Path root, Path module) {
        return module.equals(root) ? "." : root.relativize(module).toString().replace('\\', '/');
    }

    private static String parentProblem(Path module, Document model, Map<Path,Document> models) {
                var parents = model.getElementsByTagName("parent");
                for (int i = 0; i < parents.getLength(); i++) {
                    Element parent = (Element) parents.item(i);
                    String relative = child(parent, "relativePath");
                    if (relative != null && relative.isBlank()) return "external_parent";
                    Path parentPath = module.resolve(relative == null ? "../pom.xml" : relative).normalize();
                    Document parentModel = models.get(parentPath.getParent());
                    if (parentModel == null || !parentPath.getFileName().toString().equals("pom.xml")) {
                        return "external_parent";
                    }
                    for (String coordinate : List.of("groupId", "artifactId", "version")) {
                        String expected = child(parent, coordinate);
                        String actual = child(parentModel.getDocumentElement(), coordinate);
                        if (actual == null && !coordinate.equals("artifactId")) {
                            var inherited = parentModel.getElementsByTagName("parent");
                            if (inherited.getLength() > 0) actual = child((Element) inherited.item(0), coordinate);
                        }
                        if (expected == null || expected.contains("${") || !expected.equals(actual)) {
                            return "unresolved_parent";
                        }
                    }
                }
        return null;
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
