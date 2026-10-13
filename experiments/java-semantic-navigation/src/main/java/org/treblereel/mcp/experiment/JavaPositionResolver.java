package org.treblereel.mcp.experiment;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;

/** Standalone experiment: only public compiler APIs, no processor execution or code generation. */
public final class JavaPositionResolver implements AutoCloseable {
    public record Result(String status, String owner, String kind, String name,
                         String descriptor, String declarationFile, long generation, boolean reused) {}
    private record Inputs(Map<Path, String> sources, String fingerprint) {}
    private record Candidate(Element element, long width, int priority) {}
    private static final class Snapshot implements AutoCloseable {
        final StandardJavaFileManager manager;
        final Trees trees;
        final Elements elements;
        final Types types;
        final List<CompilationUnitTree> units;
        final boolean errors;
        final String fingerprint;
        Snapshot(StandardJavaFileManager manager, JavacTask task, List<CompilationUnitTree> units,
                 boolean errors, String fingerprint) {
            this.manager = manager; this.trees = Trees.instance(task);
            this.elements = task.getElements(); this.types = task.getTypes();
            this.units = units; this.errors = errors; this.fingerprint = fingerprint;
        }
        public void close() throws IOException { manager.close(); }
    }

    private final List<Path> roots;
    private final List<Path> classpath;
    private final String release;
    private final Runnable beforeValidation;
    private Snapshot snapshot;
    private long generation;
    private int closedSnapshots;

    public JavaPositionResolver(List<Path> roots, List<Path> classpath, String release) {
        this(roots, classpath, release, () -> {});
    }
    JavaPositionResolver(List<Path> roots, List<Path> classpath, String release, Runnable beforeValidation) {
        this.roots = roots.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        this.classpath = classpath.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        this.release = release;
        this.beforeValidation = beforeValidation;
    }

    public synchronized Result resolve(Path requested, int offset) throws Exception {
        Path file = requested.toAbsolutePath().normalize();
        Inputs inputs = capture();
        String text = inputs.sources().get(file);
        if (text == null) return status("source_not_in_module", false);
        if (offset < 0 || offset >= text.length()) return status("invalid_position", false);
        boolean reused = snapshot != null && snapshot.fingerprint.equals(inputs.fingerprint());
        if (!reused) {
            close();
            snapshot = build(inputs);
            generation++;
        }
        Result result = snapshot.errors ? status("analysis_errors", reused) : lookup(snapshot, file, offset, reused);
        beforeValidation.run();
        if (!capture().fingerprint().equals(inputs.fingerprint())) {
            close();
            return status("inputs_changed", false);
        }
        return result;
    }

    private Inputs capture() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, release);
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path root : roots) {
            update(digest, root.toString());
            List<Path> files;
            if (Files.isRegularFile(root)) files = List.of(root);
            else if (Files.isDirectory(root)) {
                try (var stream = Files.walk(root)) {
                    files = stream.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java")).sorted().toList();
                }
            } else files = List.of();
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                sources.put(file, text); update(digest, file.toString()); update(digest, text);
            }
        }
        for (Path entry : classpath) {
            update(digest, entry.toString());
            if (Files.isDirectory(entry)) {
                try (var stream = Files.walk(entry)) {
                    for (Path file : stream.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                        update(digest, file.toString()); updateFile(digest, file);
                    }
                }
            } else if (Files.isRegularFile(entry)) updateFile(digest, entry);
            else update(digest, "absent");
        }
        return new Inputs(sources, HexFormat.of().formatHex(digest.digest()));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void updateFile(MessageDigest digest, Path file) throws IOException {
        // Classpath validation must not allocate one byte array per dependency JAR.
        try (var input = Files.newInputStream(file);
             var sink = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) {
            input.transferTo(sink);
        }
    }

    private Snapshot build(Inputs inputs) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK compiler is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        boolean retained = false;
        try {
            // Every source is frozen before javac reads it, including edits restoring mtime.
            List<JavaFileObject> files = new ArrayList<>();
            for (var entry : inputs.sources().entrySet()) {
                files.add(new SimpleJavaFileObject(entry.getKey().toUri(), JavaFileObject.Kind.SOURCE) {
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) { return entry.getValue(); }
                });
            }
            List<String> options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "--release", release));
            // An explicit empty classpath avoids inheriting the worker's implementation classes.
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), manager, diagnostics, options, null, files);
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            task.analyze();
            boolean errors = diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            var model = new Snapshot(manager, task, units, errors, inputs.fingerprint());
            retained = true;
            return model;
        } finally { if (!retained) manager.close(); }
    }

    private Result lookup(Snapshot model, Path file, int offset, boolean reused) {
        var unit = model.units.stream().filter(u -> Path.of(u.getSourceFile().toUri()).equals(file))
                .findFirst().orElse(null);
        if (unit == null) return status("source_not_in_module", reused);
        List<Candidate> candidates = new ArrayList<>();
        var positions = model.trees.getSourcePositions();
        new TreePathScanner<Void, Void>() {
            void consider(Tree node, Element element, long start, long end, int priority) {
                if (element != null && start >= 0 && start <= offset && offset < end)
                    candidates.add(new Candidate(element, end - start, priority));
            }
            public Void visitIdentifier(IdentifierTree tree, Void ignored) {
                consider(tree, model.trees.getElement(getCurrentPath()), positions.getStartPosition(unit, tree),
                        positions.getEndPosition(unit, tree), 0);
                return super.visitIdentifier(tree, ignored);
            }
            public Void visitMemberSelect(MemberSelectTree tree, Void ignored) {
                long end = positions.getEndPosition(unit, tree);
                consider(tree, model.trees.getElement(getCurrentPath()), end - tree.getIdentifier().length(), end, 0);
                return super.visitMemberSelect(tree, ignored);
            }
            public Void visitMemberReference(MemberReferenceTree tree, Void ignored) {
                long end = positions.getEndPosition(unit, tree);
                consider(tree, model.trees.getElement(getCurrentPath()), end - tree.getName().length(), end, 0);
                return super.visitMemberReference(tree, ignored);
            }
            public Void visitNewClass(NewClassTree tree, Void ignored) {
                Tree identifier = tree.getIdentifier();
                if (identifier instanceof ParameterizedTypeTree parameterized) identifier = parameterized.getType();
                long start = positions.getStartPosition(unit, identifier), end = positions.getEndPosition(unit, identifier);
                if (identifier instanceof MemberSelectTree select) start = end - select.getIdentifier().length();
                consider(tree, model.trees.getElement(getCurrentPath()), start, end, 1);
                return super.visitNewClass(tree, ignored);
            }
        }.scan(unit, null);
        Candidate candidate = candidates.stream().min(Comparator.comparingLong(Candidate::width)
                .thenComparing(Comparator.comparingInt(Candidate::priority).reversed())).orElse(null);
        if (candidate == null) return status("unresolved", reused);
        Element element = candidate.element();
        if (element.getKind() == ElementKind.LOCAL_VARIABLE || element.getKind() == ElementKind.PARAMETER
                || element.getKind() == ElementKind.EXCEPTION_PARAMETER || element.getKind() == ElementKind.TYPE_PARAMETER
                || element.getKind() == ElementKind.BINDING_VARIABLE)
            return new Result("local", null, element.getKind().name(), element.getSimpleName().toString(), null, null, generation, reused);
        TypeElement owner = owner(element);
        if (owner == null) return status("unresolved", reused);
        String name = element.getSimpleName().toString(), kind;
        String descriptor = null;
        if (element instanceof ExecutableElement executable) {
            boolean constructor = element.getKind() == ElementKind.CONSTRUCTOR;
            if (constructor && (owner.getNestingKind() == NestingKind.ANONYMOUS || owner.getNestingKind() == NestingKind.LOCAL))
                return status("captured_constructor_descriptor_unknown", reused);
            kind = constructor ? "CONSTRUCTOR" : "METHOD";
            if (constructor) name = owner.getSimpleName().toString();
            var erased = (ExecutableType) model.types.erasure(executable.asType());
            StringBuilder value = new StringBuilder("(");
            if (constructor && owner.getNestingKind() == NestingKind.MEMBER && !owner.getModifiers().contains(Modifier.STATIC))
                value.append(descriptor(model, owner.getEnclosingElement().asType()));
            for (TypeMirror parameter : erased.getParameterTypes()) value.append(descriptor(model, parameter));
            value.append(')').append(constructor ? "V" : descriptor(model, erased.getReturnType()));
            descriptor = value.toString();
        } else if (element instanceof TypeElement) kind = "CLASS";
        else if (element instanceof VariableElement) {
            kind = "FIELD"; descriptor = descriptor(model, element.asType());
        } else return status("unresolved", reused);
        TreePath declaration = model.trees.getPath(element);
        String source = declaration == null ? null : Path.of(declaration.getCompilationUnit().getSourceFile().toUri()).toString();
        return new Result("resolved", model.elements.getBinaryName(owner).toString(), kind, name, descriptor, source, generation, reused);
    }

    private static TypeElement owner(Element element) {
        while (element != null && !(element instanceof TypeElement)) element = element.getEnclosingElement();
        return (TypeElement) element;
    }

    private static String descriptor(Snapshot model, TypeMirror type) {
        TypeMirror erased = model.types.erasure(type);
        return switch (erased.getKind()) {
            case BOOLEAN -> "Z"; case BYTE -> "B"; case SHORT -> "S"; case INT -> "I";
            case LONG -> "J"; case CHAR -> "C"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
            case ARRAY -> "[" + descriptor(model, ((ArrayType) erased).getComponentType());
            case DECLARED -> "L" + model.elements.getBinaryName((TypeElement) ((DeclaredType) erased).asElement()).toString().replace('.', '/') + ";";
            default -> throw new IllegalArgumentException("Unsupported descriptor type: " + erased.getKind());
        };
    }

    private Result status(String status, boolean reused) {
        return new Result(status, null, null, null, null, null, generation, reused);
    }
    public synchronized int closedSnapshots() { return closedSnapshots; }
    public synchronized void close() throws IOException {
        if (snapshot != null) {
            Snapshot old = snapshot; snapshot = null; closedSnapshots++; old.close();
        }
    }
}
