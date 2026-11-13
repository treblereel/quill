package org.treblereel.mcp.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectCodeExpectation;

/** Classifies whether main compiled outputs are absent, stale, or current. */
public final class CompiledOutputInspector {

    /** Checks all discovered source-set outputs without running Maven or Gradle. */
    public static boolean outputsChangedAfter(Path root, java.time.Instant indexedAt) throws IOException {
        return outputsChangedAfter(ProjectLayout.discoverClassesDirs(root,false),indexedAt);
    }
    private static boolean outputsChangedAfter(ProjectLayout.ClassesDiscovery discovery,java.time.Instant indexedAt) throws IOException {
        boolean[] changed={false};
        for(Path output:discovery.classesDirectories()) {
            Files.walkFileTree(output,new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    if(file.toString().endsWith(".class") && (attributes.isRegularFile() || Files.isRegularFile(file))) {
                        var modified=attributes.isSymbolicLink()?Files.getLastModifiedTime(file):attributes.lastModifiedTime();
                        if(modified.toInstant().isAfter(indexedAt)){changed[0]=true;return java.nio.file.FileVisitResult.TERMINATE;}
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            if(changed[0])return true;
        }
        return false;
    }
    public record Observation(Report report,boolean changedAfterIndex) {}
    public static Observation observe(Path projectRoot,java.time.Instant indexedAt) throws IOException {
        Path root=projectRoot.toAbsolutePath().normalize();
        boolean metadataOnly=metadataOnly(root);
        var discovery=ProjectLayout.discoverClassesDirs(root,false);
        var report=metadataOnly?new Report(State.NOT_APPLICABLE,List.of()):inspect(root,null,discovery);
        return new Observation(report,outputsChangedAfter(discovery,indexedAt));
    }

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
        if(metadataOnly(root))return new Report(State.NOT_APPLICABLE,List.of());
        return inspect(root,modules,ProjectLayout.discoverClassesDirs(root,false));
    }
    static Report inspect(Path root,java.util.Collection<String> modules,ProjectLayout.ClassesDiscovery discovery) {
        return capture(root, discovery).inspect(modules);
    }

    public record Module(long content, long buildInput, long output, boolean hasOutput,
            com.fasterxml.jackson.databind.JsonNode compilation, boolean requiresClasses) {
        public Module(long content, long buildInput, long output, boolean hasOutput,
                com.fasterxml.jackson.databind.JsonNode compilation) { this(content, buildInput, output, hasOutput, compilation, true); }
        public Module(long content, long buildInput, long output, boolean hasOutput) { this(content, buildInput, output, hasOutput, null); }
    }

    /** Timestamp observations are collected once; scope projections never touch the filesystem. */
    public record Snapshot(Map<String, Module> modules, long sharedBuildInput, boolean metadataOnly,
            List<com.fasterxml.jackson.databind.JsonNode> sourceSets) {
        public Snapshot(Map<String, Module> modules, long sharedBuildInput, boolean metadataOnly) {
            this(modules, sharedBuildInput, metadataOnly, List.of());
        }
        public Snapshot { modules = Map.copyOf(modules); sourceSets = List.copyOf(sourceSets); }
        public Report inspect(java.util.Collection<String> selected) {
            if (metadataOnly) return new Report(State.NOT_APPLICABLE, List.of());
            var scoped = modules.entrySet().stream()
                    .filter(e -> selected == null || selected.contains(e.getKey())).toList();
            if (!scoped.isEmpty() && scoped.stream().noneMatch(e -> e.getValue().requiresClasses()))
                return new Report(State.NOT_APPLICABLE, List.of());
            boolean hasOutputs = modules.entrySet().stream()
                    .anyMatch(e -> (selected == null || selected.contains(e.getKey())) && e.getValue().hasOutput());
            if (!hasOutputs) return new Report(State.MISSING, modules.entrySet().stream()
                    .filter(e -> (selected == null || selected.contains(e.getKey())) && e.getValue().requiresClasses() && e.getValue().content() > 0)
                    .map(Map.Entry::getKey).sorted().toList());
            long shared = sharedBuildInput;
            if (selected != null) for (String name : selected) {
                Module module = modules.get(name);
                if (module != null && module.requiresClasses() && module.content() == 0 && !module.hasOutput())
                    shared = Math.max(shared, module.buildInput());
            }
            List<String> stale = new ArrayList<>();
            for (var entry : modules.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (selected != null && !selected.contains(entry.getKey())) continue;
                Module module = entry.getValue();
                if (!module.requiresClasses()) continue;
                if (module.content() == 0 && !module.hasOutput()) continue;
                boolean confirmed = module.compilation() != null && module.compilation().path("status").asText().equals("matching");
                boolean changed = module.compilation() != null && module.compilation().path("status").asText().equals("changed");
                if (module.output() == 0 || changed || !confirmed && Math.max(shared, Math.max(module.content(), module.buildInput())) > module.output())
                    stale.add(entry.getKey());
            }
            return new Report(stale.isEmpty() ? State.FRESH : State.STALE, stale);
        }
    }

    public static Snapshot capture(Path root, ProjectLayout.ClassesDiscovery discovery) {
        return capture(root, discovery, new java.util.HashMap<>());
    }

    static Snapshot capture(Path root, ProjectLayout.ClassesDiscovery discovery,
            Map<Path, com.fasterxml.jackson.databind.node.ObjectNode> observed) {
        var outputs = discovery.outputs().stream().filter(output -> output.sourceSet().equals("main"))
                .collect(java.util.stream.Collectors.groupingBy(ProjectLayout.CompiledOutput::moduleDirectory));
        Map<String, Module> modules = new LinkedHashMap<>();
        for (Path module : discovery.moduleDirectories()) {
            var compiled = outputs.getOrDefault(module, List.of());
            var evidence = compilation(module, "main", compiled.stream().map(ProjectLayout.CompiledOutput::directory).toList(), observed);
            long output = compiled.stream().mapToLong(directory -> newestModified(directory.directory(), false)).max().orElse(0);
            // An output directory holding only copied resources is not bytecode evidence.
            modules.put(moduleName(root, module), new Module(newestMainContent(module), newestModuleBuildInput(module),
                    output, !compiled.isEmpty(), evidence, output > 0 || evidence != null || !resourceOnlyMain(module)));
        }
        var sourceSets = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (Path module : discovery.moduleDirectories()) {
            var sets = discovery.outputs().stream().filter(output -> output.moduleDirectory().equals(module))
                    .map(ProjectLayout.CompiledOutput::sourceSet).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            sets.add("main"); sets.add("test");
            for (String set : sets) {
                var evidence = set.equals("main") ? modules.get(moduleName(root, module)).compilation() : compilation(module, set, discovery.outputs().stream()
                        .filter(output -> output.moduleDirectory().equals(module) && output.sourceSet().equals(set))
                        .map(ProjectLayout.CompiledOutput::directory).toList(), observed);
                if (evidence != null) {
                    var item = (com.fasterxml.jackson.databind.node.ObjectNode) evidence.deepCopy();
                    item.put("module", moduleName(root, module)).put("source_set", set); sourceSets.add(item);
                }
            }
        }
        return new Snapshot(modules, newestSharedBuildInput(root), metadataOnly(root), sourceSets);
    }

    static com.fasterxml.jackson.databind.JsonNode compilation(Path module, String sourceSet) { return compilation(module, sourceSet, null); }

    static com.fasterxml.jackson.databind.JsonNode compilation(Path module, String sourceSet, List<Path> expectedOutputs) {
        return compilation(module, sourceSet, expectedOutputs, new java.util.HashMap<>());
    }

    private static com.fasterxml.jackson.databind.JsonNode compilation(Path module, String sourceSet, List<Path> expectedOutputs,
            Map<Path, com.fasterxml.jackson.databind.node.ObjectNode> observed) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var captures = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        com.fasterxml.jackson.databind.JsonNode invalid = null;
        for (String directory : Files.isRegularFile(module.resolve("pom.xml")) ? List.of("target") : List.of("build")) for (String language : List.of("java", "kotlin")) {
            Path file = module.resolve(directory + "/quill-" + language + "-" + sourceSet + ".properties");
            if (!Files.isRegularFile(file)) continue;
            var data = observed.computeIfAbsent(file, path -> JavaCompilerSettings.read(module, sourceSet, path));
            var evidence = data.get("compilation");
            if (evidence != null) {
                Path capturedOutput = Path.of(evidence.path("output").asText());
                boolean bound = expectedOutputs == null || expectedOutputs.stream().anyMatch(output -> {
                    try { return Files.isSameFile(output, capturedOutput); }
                    catch (IOException unavailable) { return false; }
                });
                if (!bound) {
                    evidence = evidence.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put("status", "changed").put("evidence", "output_scope_mismatch");
                }
                captures.put(language, evidence);
            }
            else if (data.has("compilation_protocol") && java.util.Set.of("invalid", "unsupported_protocol").contains(data.path("status").asText()))
                invalid = json.createObjectNode().put("status", "changed").put("detail", data.path("detail").asText());
        }
        if (captures.isEmpty()) return invalid;
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) captures.values().iterator().next().deepCopy();
        var required = new java.util.TreeSet<String>();
        captures.values().forEach(evidence -> evidence.path("required_languages").forEach(language -> required.add(language.asText())));
        boolean matching = invalid == null && captures.keySet().containsAll(required)
                && captures.values().stream().allMatch(evidence -> evidence.path("status").asText().equals("matching"));
        result.put("status", matching ? "matching" : "changed");
        var compilerStatus = result.putObject("compiler_statuses");
        var changedInputs = new java.util.TreeSet<String>();
        captures.forEach((language, evidence) -> {
            compilerStatus.put(language, evidence.path("status").asText());
            evidence.path("changed_inputs").forEach(input -> changedInputs.add(input.asText()));
            if (!evidence.path("status").asText().equals("matching"))
                changedInputs.add(language+":"+evidence.path("evidence").asText("invalid_receipt"));
        });
        for (String language : required) if (!captures.containsKey(language)) changedInputs.add("compiler_evidence_missing:"+language);
        result.set("changed_inputs", json.valueToTree(changedInputs));
        result.set("required_languages", json.valueToTree(required));
        return result;
    }

    static boolean metadataOnly(Path root) {
        return ProjectCodeExpectation.inspect(root,BuildSystem.detect(root))==ProjectCodeExpectation.State.METADATA_ONLY;
    }

    /** Maven standard-layout directories under src/main that are copied or packaged, never compiled. */
    private static final List<String> NON_COMPILED_MAIN = List.of("resources", "scripts", "filters", "config", "assembly");

    private static boolean resourceOnlyMain(Path module) {
        var nonCompiled = NON_COMPILED_MAIN.stream().map(name -> module.resolve("src/main").resolve(name)).toList();
        if (!Files.isRegularFile(module.resolve("pom.xml")) || nonCompiled.stream().noneMatch(Files::isDirectory)) return false;
        try {
            String pom = Files.readString(module.resolve("pom.xml"));
            // Without compiler evidence, custom/external source roots remain class-producing.
            if (pom.contains("sourceDirectory") || pom.contains("build-helper-maven-plugin") || pom.contains("kotlin-maven-plugin")) return false;
            var tests = List.of(module.resolve("src/test"), module.resolve("src/integration-test"),
                    module.resolve("target/test-classes"), module.resolve("target/generated-test-sources"));
            try (var paths = Files.walk(module)) {
                return paths.noneMatch(path -> Files.isSymbolicLink(path)
                        || (path.toString().endsWith(".java") || path.toString().endsWith(".kt")
                            || Files.isRegularFile(path) && path.startsWith(module.resolve("src/main"))
                                && nonCompiled.stream().noneMatch(path::startsWith))
                        && tests.stream().noneMatch(path::startsWith));
            }
        } catch (IOException | java.io.UncheckedIOException failure) {
            return false;
        }
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
        long[] newest={0};
        try {
            Files.walkFileTree(directory,new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    if((attributes.isRegularFile() || Files.isRegularFile(path)) && (!buildInputsOnly || isBuildInput(path))) {
                        var time=attributes.isSymbolicLink()?Files.getLastModifiedTime(path):attributes.lastModifiedTime();
                        newest[0]=Math.max(newest[0],time.toMillis());
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return newest[0];
        } catch(IOException ignored){return 0;}
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
