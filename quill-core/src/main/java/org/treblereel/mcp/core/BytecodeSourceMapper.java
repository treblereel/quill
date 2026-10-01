package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/** Maps JVM classes to sources using the compiler-emitted SourceFile attribute. */
final class BytecodeSourceMapper {

    private BytecodeSourceMapper() {}

    static Map<String, Path> map(
            ClassFileSnapshot snapshot, List<Path> sourceRoots) {
        Map<String, Path> sources = sourcesByRelativePath(sourceRoots);
        Map<String, Path> result = new HashMap<>();
        for (ClassFileSnapshot.Entry entry : snapshot.entries()) {
            ClassSource classSource = classSource(entry);
            if (classSource == null) continue;
            Path source = sources.get(classSource.relativeSource());
            if (source != null) result.putIfAbsent(classSource.className(), source);
        }
        return Map.copyOf(result);
    }

    static Path source(ClassFileSnapshot.Entry entry, List<Path> sourceRoots) {
        ClassSource classSource = classSource(entry);
        return classSource == null
                ? null : sourcesByRelativePath(sourceRoots).get(classSource.relativeSource());
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

    private static Map<String, Path> sourcesByRelativePath(List<Path> sourceRoots) {
        Map<String, Path> result = new LinkedHashMap<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(JvmSourceFiles::isSourceFile)
                        .sorted()
                        .forEach(path -> result.putIfAbsent(
                                root.relativize(path).toString().replace('\\', '/'), path));
            } catch (IOException ignored) {
                // Unreadable roots simply cannot contribute source mappings.
            }
        }
        return result;
    }

    private record ClassSource(String className, String relativeSource) {}
}
