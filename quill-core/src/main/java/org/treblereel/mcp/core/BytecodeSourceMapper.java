package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/** Maps JVM classes to sources using the compiler-emitted SourceFile attribute. */
final class BytecodeSourceMapper {

    private BytecodeSourceMapper() {}

    static Map<String, Path> map(
            ClassFileSnapshot snapshot, List<Path> sourceRoots) {
        Map<String, List<Path>> sources = sourcesByRelativePath(sourceRoots);
        Map<String, Path> result = new HashMap<>();
        for (ClassFileSnapshot.Entry entry : snapshot.entries()) {
            ClassSource classSource = classSource(entry);
            if (classSource == null) continue;
            Path source = select(sources.get(classSource.relativeSource()), entry);
            if (source != null) result.putIfAbsent(classSource.className(), source);
        }
        return Map.copyOf(result);
    }

    static Path source(ClassFileSnapshot.Entry entry, List<Path> sourceRoots) {
        return source(entry, sourcesByRelativePath(sourceRoots));
    }

    static Path source(ClassFileSnapshot.Entry entry, Map<String, List<Path>> sources) {
        ClassSource classSource = classSource(entry);
        return classSource == null ? null : select(sources.get(classSource.relativeSource()), entry);
    }

    private static ClassSource classSource(ClassFileSnapshot.Entry entry) {
        try {
            ClassReader reader = new ClassReader(entry.bytecode());
            String className = reader.getClassName().replace('/', '.');
            String packagePath = packagePath(reader.getClassName());
            String[] sourceFile = new String[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitSource(String source, String debug) {
                    sourceFile[0] = source;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            if (sourceFile[0] == null || sourceFile[0].isBlank()) return null;
            String relative = packagePath.isEmpty()
                    ? sourceFile[0] : packagePath + "/" + sourceFile[0];
            return new ClassSource(className, relative);
        } catch (RuntimeException ignored) {
            // The regular indexer reports malformed bytecode; source mapping stays best effort.
            return null;
        }
    }

    private static String packagePath(String internalClassName) {
        int separator = internalClassName.lastIndexOf('/');
        return separator < 0 ? "" : internalClassName.substring(0, separator);
    }

    static Map<String, List<Path>> sourcesByRelativePath(List<Path> sourceRoots) {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(JvmSourceFiles::isSourceFile)
                        .sorted()
                        .forEach(path -> {
                            String relative = root.relativize(path).toString().replace('\\', '/');
                            if (path.toString().endsWith(".kt")) {
                                try {
                                    String pkg = KotlinSourceText.packageName(Files.readString(path));
                                    relative = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/")
                                            + path.getFileName();
                                } catch (IOException ignored) { return; }
                            }
                            result.computeIfAbsent(relative, key -> new ArrayList<>()).add(path);
                        });
            } catch (IOException ignored) {
                // Unreadable roots simply cannot contribute source mappings.
            }
        }
        return result;
    }

    private static Path select(List<Path> paths, ClassFileSnapshot.Entry entry) {
        if (paths == null) return null;
        List<Path> candidates = new ArrayList<>(new LinkedHashSet<>(paths));
        Path output = entry.classesDirectory().toAbsolutePath().normalize();
        Path module = output;
        while (module != null && module.getFileName() != null && !module.getFileName().toString().equals("build")
                && !module.getFileName().toString().equals("target")) module = module.getParent();
        if (module != null && module.getParent() != null) {
            Path owner = module.getParent();
            var local = candidates.stream().filter(path -> path.toAbsolutePath().normalize()
                    .startsWith(owner)).toList();
            if (local.isEmpty()) return null;
            candidates = new ArrayList<>(local);
            String set = output.toString().replace('\\', '/').matches(".*/(?:test|test-classes)$")
                    ? "test" : "main";
            var sameSet = candidates.stream().filter(path -> path.toString().replace('\\', '/')
                    .contains("/" + set + "/")).toList();
            if (!sameSet.isEmpty()) candidates = new ArrayList<>(sameSet);
            else candidates.removeIf(path -> path.toString().replace('\\', '/')
                    .matches(".*/src/(?:main|test)/.*"));
        }
        // Duplicate declarations must remain unresolved instead of picking a random file.
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private record ClassSource(String className, String relativeSource) {}
}
