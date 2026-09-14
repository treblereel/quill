package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.plexus.util.xml.pull.MXParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Resolves external Maven dependencies without requiring reactor artifacts to be installed. */
final class MavenClasspathResolver {

    private static final int DIAGNOSTIC_LIMIT = 2_000;

    private MavenClasspathResolver() {}

    static DependencyIndexer.ClasspathGenerationResult generate(Path projectRoot) {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("quill-maven-classpath-");
            Path effectivePom = temporary.resolve("effective-pom.xml");
            ProcessResult effective = run(projectRoot, BuildSystem.MAVEN.command(projectRoot,
                    "help:effective-pom", "-Doutput=" + effectivePom, "-q"), temporary);
            if (effective.exitCode() != 0 || !Files.isRegularFile(effectivePom)) {
                return failure("Maven effective POM generation", effective);
            }

            List<EffectiveProject> projects = readEffectiveProjects(effectivePom, projectRoot);
            if (projects.isEmpty()) {
                return DependencyIndexer.ClasspathGenerationResult.failure(
                        "Maven effective POM did not contain reactor projects");
            }
            Set<ArtifactCoordinate> reactor = new LinkedHashSet<>();
            for (EffectiveProject project : projects) {
                reactor.add(new ArtifactCoordinate(
                        project.groupId(), project.artifactId(), project.version()));
            }

            List<SyntheticModule> modules = new ArrayList<>();
            for (EffectiveProject project : projects) {
                if (project.moduleDirectory() == null) continue;
                List<Dependency> external = project.dependencies().stream()
                        .filter(Dependency::isRuntime)
                        .filter(dependency -> !reactor.contains(dependency.artifactCoordinate()))
                        .toList();
                modules.add(new SyntheticModule(project.moduleDirectory(), external));
            }
            if (modules.isEmpty()) {
                return DependencyIndexer.ClasspathGenerationResult.failure(
                        "Maven effective POM did not identify reactor module directories");
            }

            Path syntheticRoot = Files.createDirectories(temporary.resolve("reactor"));
            writeSyntheticReactor(syntheticRoot, modules, projects);
            ProcessResult resolved = run(projectRoot, BuildSystem.MAVEN.command(projectRoot,
                    "-f", syntheticRoot.resolve("pom.xml").toString(), "--fail-at-end",
                    "dependency:build-classpath", "-DincludeScope=runtime",
                    "-Dmdep.outputFile=target/quill-classpath.txt", "-q"), temporary);

            int copied = publishClasspaths(syntheticRoot, modules);
            if (resolved.exitCode() != 0) {
                return failure("Maven external dependency classpath generation ("
                        + copied + "/" + modules.size() + " modules resolved)", resolved);
            }
            if (copied != modules.size()) {
                return DependencyIndexer.ClasspathGenerationResult.failure(
                        "Maven external dependency classpath generation produced " + copied + "/"
                                + modules.size() + " module classpaths");
            }
            return DependencyIndexer.ClasspathGenerationResult.success();
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            return DependencyIndexer.ClasspathGenerationResult.failure(
                    "Could not resolve Maven reactor classpath: " + rootMessage(e));
        } finally {
            deleteTree(temporary);
        }
    }

    private static ProcessResult run(Path workingDirectory, List<String> command, Path temporary)
            throws IOException {
        Path log = Files.createTempFile(temporary, "maven-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, diagnostic(log));
        } catch (InterruptedException e) {
            try (var descendants = process.descendants()) {
                descendants.forEach(ProcessHandle::destroyForcibly);
            }
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new ProcessResult(130, "resolution interrupted");
        }
    }

    private static String diagnostic(Path log) {
        try {
            String value = Files.readString(log, StandardCharsets.UTF_8).trim();
            if (value.length() <= DIAGNOSTIC_LIMIT) return value;
            return value.substring(value.length() - DIAGNOSTIC_LIMIT).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static DependencyIndexer.ClasspathGenerationResult failure(
            String operation, ProcessResult result) {
        String detail = operation + " exited with code " + result.exitCode();
        if (!result.diagnostic().isBlank()) detail += ": " + result.diagnostic();
        System.err.println("[quill] Warning: " + detail);
        return DependencyIndexer.ClasspathGenerationResult.failure(detail);
    }

    private static int publishClasspaths(Path syntheticRoot, List<SyntheticModule> modules)
            throws IOException {
        int copied = 0;
        for (int i = 0; i < modules.size(); i++) {
            Path generated = syntheticRoot.resolve("module-" + i)
                    .resolve("target/quill-classpath.txt");
            if (!Files.isRegularFile(generated)) continue;
            Path destination = BuildSystem.MAVEN.classpathFile(modules.get(i).moduleDirectory());
            Files.createDirectories(destination.getParent());
            Files.copy(generated, destination, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }

    private static void writeSyntheticReactor(Path root, List<SyntheticModule> modules,
            List<EffectiveProject> projects) throws IOException {
        Set<Repository> repositories = new LinkedHashSet<>();
        for (EffectiveProject project : projects) repositories.addAll(project.repositories());

        StringBuilder parent = new StringBuilder(pomHeader())
                .append("  <groupId>org.treblereel.quill.internal</groupId>\n")
                .append("  <artifactId>classpath-reactor</artifactId>\n")
                .append("  <version>1</version>\n  <packaging>pom</packaging>\n")
                .append("  <modules>\n");
        for (int i = 0; i < modules.size(); i++) {
            parent.append("    <module>module-").append(i).append("</module>\n");
        }
        parent.append("  </modules>\n");
        appendRepositories(parent, repositories, "  ");
        parent.append("</project>\n");
        Files.writeString(root.resolve("pom.xml"), parent, StandardCharsets.UTF_8);

        for (int i = 0; i < modules.size(); i++) {
            Path directory = Files.createDirectories(root.resolve("module-" + i));
            StringBuilder pom = new StringBuilder(pomHeader())
                    .append("  <parent>\n")
                    .append("    <groupId>org.treblereel.quill.internal</groupId>\n")
                    .append("    <artifactId>classpath-reactor</artifactId>\n")
                    .append("    <version>1</version>\n")
                    .append("  </parent>\n")
                    .append("  <artifactId>module-").append(i).append("</artifactId>\n")
                    .append("  <dependencies>\n");
            for (Dependency dependency : modules.get(i).dependencies()) {
                appendDependency(pom, dependency);
            }
            pom.append("  </dependencies>\n</project>\n");
            Files.writeString(directory.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        }
    }

    private static String pomHeader() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                """;
    }

    private static void appendDependency(StringBuilder xml, Dependency dependency) {
        xml.append("    <dependency>\n")
                .append("      <groupId>").append(escape(dependency.groupId()))
                .append("</groupId>\n")
                .append("      <artifactId>").append(escape(dependency.artifactId()))
                .append("</artifactId>\n")
                .append("      <version>").append(escape(dependency.version()))
                .append("</version>\n");
        appendOptionalElement(xml, "type", dependency.type(), "      ");
        appendOptionalElement(xml, "classifier", dependency.classifier(), "      ");
        appendOptionalElement(xml, "scope", dependency.scope(), "      ");
        if (!dependency.exclusions().isEmpty()) {
            xml.append("      <exclusions>\n");
            for (Coordinate exclusion : dependency.exclusions()) {
                xml.append("        <exclusion>\n")
                        .append("          <groupId>").append(escape(exclusion.groupId()))
                        .append("</groupId>\n")
                        .append("          <artifactId>").append(escape(exclusion.artifactId()))
                        .append("</artifactId>\n")
                        .append("        </exclusion>\n");
            }
            xml.append("      </exclusions>\n");
        }
        xml.append("    </dependency>\n");
    }

    private static void appendRepositories(
            StringBuilder xml, Set<Repository> repositories, String indent) {
        if (repositories.isEmpty()) return;
        xml.append(indent).append("<repositories>\n");
        for (Repository repository : repositories) {
            if (repository.id().isBlank() || repository.url().isBlank()) continue;
            xml.append(indent).append("  <repository>\n")
                    .append(indent).append("    <id>").append(escape(repository.id()))
                    .append("</id>\n")
                    .append(indent).append("    <url>").append(escape(repository.url()))
                    .append("</url>\n");
            if (!repository.releasesEnabled().isBlank()) {
                xml.append(indent).append("    <releases><enabled>")
                        .append(escape(repository.releasesEnabled()))
                        .append("</enabled></releases>\n");
            }
            if (!repository.snapshotsEnabled().isBlank()) {
                xml.append(indent).append("    <snapshots><enabled>")
                        .append(escape(repository.snapshotsEnabled()))
                        .append("</enabled></snapshots>\n");
            }
            xml.append(indent).append("  </repository>\n");
        }
        xml.append(indent).append("</repositories>\n");
    }

    private static void appendOptionalElement(
            StringBuilder xml, String name, String value, String indent) {
        if (value != null && !value.isBlank()) {
            xml.append(indent).append('<').append(name).append('>')
                    .append(escape(value)).append("</").append(name).append(">\n");
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static List<EffectiveProject> readEffectiveProjects(Path effectivePom, Path root)
            throws IOException, XmlPullParserException {
        List<EffectiveProject> result = new ArrayList<>();
        try (Reader input = Files.newBufferedReader(effectivePom, StandardCharsets.UTF_8)) {
            XmlPullParser reader = new MXParser();
            reader.setInput(input);
            List<String> path = new ArrayList<>();
            ProjectBuilder project = null;
            DependencyBuilder dependency = null;
            CoordinateBuilder exclusion = null;
            RepositoryBuilder repository = null;
            int projectDepth = -1;
            int event;
            while ((event = reader.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = reader.getName();
                    path.add(name);
                    if (name.equals("project") && project == null) {
                        project = new ProjectBuilder();
                        projectDepth = path.size() - 1;
                        continue;
                    }
                    if (project == null) continue;
                    List<String> relative = path.subList(projectDepth, path.size());
                    if (matches(relative, "project", "dependencies", "dependency")) {
                        dependency = new DependencyBuilder();
                    } else if (matches(relative, "project", "dependencies", "dependency",
                            "exclusions", "exclusion")) {
                        exclusion = new CoordinateBuilder();
                    } else if (matches(relative, "project", "repositories", "repository")) {
                        repository = new RepositoryBuilder();
                    } else if (isValuePath(relative)) {
                        String value = reader.nextText().trim();
                        assign(relative, value, project, dependency, exclusion, repository);
                        path.remove(path.size() - 1);
                    }
                } else if (event == XmlPullParser.END_TAG && project != null) {
                    List<String> relative = path.subList(projectDepth, path.size());
                    if (matches(relative, "project", "dependencies", "dependency",
                            "exclusions", "exclusion")) {
                        if (dependency != null && exclusion != null
                                && exclusion.groupId != null && exclusion.artifactId != null) {
                            dependency.exclusions.add(exclusion.build());
                        }
                        exclusion = null;
                    } else if (matches(relative, "project", "dependencies", "dependency")) {
                        project.dependencies.add(dependency.build());
                        dependency = null;
                    } else if (matches(relative, "project", "repositories", "repository")) {
                        project.repositories.add(repository.build());
                        repository = null;
                    } else if (matches(relative, "project")) {
                        EffectiveProject built = project.build(root);
                        if (built.groupId() != null && built.artifactId() != null) result.add(built);
                        project = null;
                        projectDepth = -1;
                    }
                    path.remove(path.size() - 1);
                } else if (event == XmlPullParser.END_TAG && !path.isEmpty()) {
                    path.remove(path.size() - 1);
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean isValuePath(List<String> path) {
        if (path.size() == 2) {
            return path.get(1).equals("groupId") || path.get(1).equals("artifactId")
                    || path.get(1).equals("version");
        }
        if (matches(path, "project", "build", "directory")) return true;
        if (path.size() == 4 && matchesPrefix(path, "project", "dependencies", "dependency")) {
            return Set.of("groupId", "artifactId", "version", "type", "classifier", "scope")
                    .contains(path.get(3));
        }
        if (path.size() == 6 && matchesPrefix(path, "project", "dependencies", "dependency",
                "exclusions", "exclusion")) {
            return path.get(5).equals("groupId") || path.get(5).equals("artifactId");
        }
        if (path.size() == 4 && matchesPrefix(path, "project", "repositories", "repository")) {
            return path.get(3).equals("id") || path.get(3).equals("url");
        }
        return path.size() == 5 && matchesPrefix(path, "project", "repositories", "repository")
                && (path.get(3).equals("releases") || path.get(3).equals("snapshots"))
                && path.get(4).equals("enabled");
    }

    private static void assign(List<String> path, String value, ProjectBuilder project,
            DependencyBuilder dependency, CoordinateBuilder exclusion,
            RepositoryBuilder repository) {
        if (path.size() == 2) {
            if (path.get(1).equals("groupId")) project.groupId = value;
            if (path.get(1).equals("artifactId")) project.artifactId = value;
            if (path.get(1).equals("version")) project.version = value;
        } else if (matches(path, "project", "build", "directory")) {
            project.buildDirectory = value;
        } else if (path.size() == 4 && dependency != null) {
            dependency.assign(path.get(3), value);
        } else if (path.size() == 6 && exclusion != null) {
            exclusion.assign(path.get(5), value);
        } else if (path.size() == 4 && repository != null) {
            repository.assign(path.get(3), value);
        } else if (path.size() == 5 && repository != null) {
            if (path.get(3).equals("releases")) repository.releasesEnabled = value;
            if (path.get(3).equals("snapshots")) repository.snapshotsEnabled = value;
        }
    }

    private static boolean matches(List<String> actual, String... expected) {
        return actual.size() == expected.length && matchesPrefix(actual, expected);
    }

    private static boolean matchesPrefix(List<String> actual, String... expected) {
        if (actual.size() < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (!actual.get(i).equals(expected[i])) return false;
        }
        return true;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Temporary resolver files are best-effort cleanup only.
        }
    }

    private record ProcessResult(int exitCode, String diagnostic) {}

    private record Coordinate(String groupId, String artifactId) {}

    private record ArtifactCoordinate(String groupId, String artifactId, String version) {}

    private record Dependency(String groupId, String artifactId, String version, String type,
            String classifier, String scope, List<Coordinate> exclusions) {
        ArtifactCoordinate artifactCoordinate() {
            return new ArtifactCoordinate(groupId, artifactId, version);
        }

        boolean isRuntime() {
            if (groupId == null || artifactId == null || version == null) return false;
            return scope == null || scope.isBlank() || scope.equals("compile")
                    || scope.equals("runtime");
        }
    }

    private record Repository(String id, String url, String releasesEnabled,
            String snapshotsEnabled) {}

    private record EffectiveProject(String groupId, String artifactId, String version,
            Path moduleDirectory, List<Dependency> dependencies,
            List<Repository> repositories) {}

    private record SyntheticModule(Path moduleDirectory, List<Dependency> dependencies) {}

    private static final class ProjectBuilder {
        String groupId;
        String artifactId;
        String version;
        String buildDirectory;
        final List<Dependency> dependencies = new ArrayList<>();
        final List<Repository> repositories = new ArrayList<>();

        EffectiveProject build(Path root) {
            Path module = null;
            if (buildDirectory != null && !buildDirectory.isBlank()) {
                Path build = Path.of(buildDirectory);
                if (!build.isAbsolute()) build = root.resolve(build);
                Path candidate = build.toAbsolutePath().normalize().getParent();
                Path normalizedRoot = realPath(root);
                candidate = realPath(candidate);
                if (candidate != null && candidate.startsWith(normalizedRoot)) module = candidate;
            }
            return new EffectiveProject(groupId, artifactId, version, module,
                    List.copyOf(dependencies), List.copyOf(repositories));
        }

        private static Path realPath(Path path) {
            if (path == null) return null;
            try {
                return path.toRealPath();
            } catch (IOException ignored) {
                return path.toAbsolutePath().normalize();
            }
        }
    }

    private static final class DependencyBuilder {
        String groupId;
        String artifactId;
        String version;
        String type;
        String classifier;
        String scope;
        final List<Coordinate> exclusions = new ArrayList<>();

        void assign(String name, String value) {
            switch (name) {
                case "groupId" -> groupId = value;
                case "artifactId" -> artifactId = value;
                case "version" -> version = value;
                case "type" -> type = value;
                case "classifier" -> classifier = value;
                case "scope" -> scope = value;
                default -> { }
            }
        }

        Dependency build() {
            return new Dependency(groupId, artifactId, version, type, classifier, scope,
                    List.copyOf(exclusions));
        }
    }

    private static final class CoordinateBuilder {
        String groupId;
        String artifactId;

        void assign(String name, String value) {
            if (name.equals("groupId")) groupId = value;
            if (name.equals("artifactId")) artifactId = value;
        }

        Coordinate build() {
            return new Coordinate(groupId, artifactId);
        }
    }

    private static final class RepositoryBuilder {
        String id = "";
        String url = "";
        String releasesEnabled = "";
        String snapshotsEnabled = "";

        void assign(String name, String value) {
            if (name.equals("id")) id = value;
            if (name.equals("url")) url = value;
        }

        Repository build() {
            return new Repository(id, url, releasesEnabled, snapshotsEnabled);
        }
    }
}
