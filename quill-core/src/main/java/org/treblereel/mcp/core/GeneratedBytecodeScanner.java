package org.treblereel.mcp.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

/**
 * Classes a Quarkus build generated for application classes ({@code X_Bean}, {@code X_ClientProxy},
 * {@code X_..._Synthetic_Bean}), read from {@code quarkus-app/quarkus/generated-bytecode.jar}.
 * Each entry names the application class it was generated for and the non-container types it
 * references, so a question about a generated class can be traced to the source to edit.
 * Dependency classes the build generated an {@code X_Bean} for are listed too: they are the
 * dependency beans, interceptors among them, that this application's container registered.
 */
public final class GeneratedBytecodeScanner {

    public static final String METADATA_KEY = "quarkus_generated_classes";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JAR = "generated-bytecode.jar";
    private static final int REFERENCE_LIMIT = 8;

    private GeneratedBytecodeScanner() {}

    /** JSON for {@link #METADATA_KEY}, or null when the project has no generated-bytecode jar. */
    public static String scan(Path root, Set<String> applicationClasses) {
        List<Path> jars = jars(root);
        if (jars.isEmpty()) return null;
        ObjectNode result = JSON.createObjectNode();
        result.put("scope", "classes generated for application classes by a Quarkus build; references exclude "
                + "JDK, Jakarta APIs and the ArC container API (io.quarkus.arc, io.quarkus.arc.impl)");
        ArrayNode jarNodes = result.putArray("jars");
        ArrayNode classes = result.putArray("classes");
        Set<String> dependencyBeans = new TreeSet<>();
        for (Path jar : jars) {
            String relative = root.relativize(jar).toString().replace('\\', '/');
            ObjectNode jarNode = jarNodes.addObject().put("path", relative);
            try {
                FileTime modified = Files.getLastModifiedTime(jar);
                jarNode.put("modified", modified.toInstant().toString());
                FileTime compiled = newestClass(jar.getParent().getParent().getParent().resolve("classes"));
                if (compiled != null) jarNode.put("older_than_compiled_classes", modified.compareTo(compiled) < 0);
                int count = 0;
                try (JarFile file = new JarFile(jar.toFile())) {
                    for (var entries = file.entries(); entries.hasMoreElements(); ) {
                        var entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class")) continue;
                        String name = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                        String source = sourceClass(name, applicationClasses);
                        if (source == null) {
                            if (name.endsWith("_Bean")) dependencyBeans.add(name.substring(0, name.length() - 5));
                            continue;
                        }
                        count++;
                        ObjectNode node = classes.addObject().put("class", name).put("jar", relative)
                                .put("entry", entry.getName()).put("source_class", source);
                        ArrayNode references = node.putArray("references");
                        try (var input = file.getInputStream(entry)) {
                            references(input.readAllBytes(), name).stream().limit(REFERENCE_LIMIT).forEach(references::add);
                        }
                    }
                }
                jarNode.put("application_classes", count);
            } catch (IOException | RuntimeException unreadable) {
                jarNode.put("status", "unreadable");
            }
        }
        ArrayNode beans = result.putArray("dependency_bean_classes");
        dependencyBeans.forEach(beans::add);
        return result.toString();
    }

    /**
     * Entry names, sizes and CRCs of the generated-bytecode jars of {@code modules}. A Quarkus rebuild
     * from unchanged inputs rewrites the jar with new timestamps but the same entries, so this matches.
     */
    public static String fingerprint(List<Path> modules) {
        StringBuilder result = new StringBuilder();
        modules.stream().map(module -> module.toAbsolutePath().normalize()).sorted().forEach(module -> {
            for (String output : List.of("target", "build")) {
                Path jar = module.resolve(output).resolve("quarkus-app/quarkus").resolve(JAR);
                if (!Files.isRegularFile(jar)) continue;
                result.append(jar).append('\n');
                try (JarFile file = new JarFile(jar.toFile())) {
                    file.stream().map(entry -> entry.getName() + ' ' + entry.getSize() + ' ' + Long.toHexString(entry.getCrc()))
                            .sorted().forEach(line -> result.append(line).append('\n'));
                } catch (IOException | RuntimeException unreadable) {
                    result.append("unreadable\n");
                }
            }
        });
        if (result.isEmpty()) return "";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The longest application class name {@code name} extends with an underscore suffix. */
    static String sourceClass(String name, Set<String> applicationClasses) {
        for (int index = name.lastIndexOf('_'); index > 0; index = name.lastIndexOf('_', index - 1)) {
            String candidate = name.substring(0, index);
            if (applicationClasses.contains(candidate)) return candidate;
        }
        return null;
    }

    static Set<String> references(byte[] bytes, String self) {
        ClassReader reader = new ClassReader(bytes);
        Set<String> names = new TreeSet<>();
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0 || reader.readByte(offset - 1) != 7) continue; // CONSTANT_Class
            String internal = reader.readUTF8(offset, buffer);
            if (internal.startsWith("[")) internal = Type.getType(internal).getElementType().getInternalName();
            String name = internal.replace('/', '.');
            if (name.equals(self) || excluded(name)) continue;
            names.add(name);
        }
        return names;
    }

    private static boolean excluded(String name) {
        if (name.startsWith("java.") || name.startsWith("jakarta.") || name.startsWith("javax.")) return true;
        String pkg = name.lastIndexOf('.') < 0 ? "" : name.substring(0, name.lastIndexOf('.'));
        return pkg.equals("io.quarkus.arc") || pkg.equals("io.quarkus.arc.impl");
    }

    private static List<Path> jars(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), 6, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(root) && (name.startsWith(".") || name.equals("node_modules") || name.equals("src"))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(JAR) && file.getParent() != null
                            && file.getParent().getFileName().toString().equals("quarkus")
                            && file.getParent().getParent() != null
                            && file.getParent().getParent().getFileName().toString().equals("quarkus-app"))
                        found.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            return List.of();
        }
        found.sort(null);
        return found;
    }

    private static FileTime newestClass(Path classes) {
        if (!Files.isDirectory(classes)) return null;
        try (var files = Files.walk(classes)) {
            return files.filter(file -> file.toString().endsWith(".class")).map(file -> {
                try {
                    return Files.getLastModifiedTime(file);
                } catch (IOException unreadable) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).max(FileTime::compareTo).orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }
}
