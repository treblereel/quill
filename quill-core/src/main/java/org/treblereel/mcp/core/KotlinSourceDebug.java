package org.treblereel.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.*;

/** Reads compiler source evidence without expanding SMAP ranges or choosing a coroutine state. */
public final class KotlinSourceDebug {
    private KotlinSourceDebug() {}
    public record Mapping(String stratum, String file, String path, int input, int repeat, int output, int increment) {
        public Mapping {
            if (input <= 0 || repeat <= 0 || output <= 0 || increment <= 0
                    || (long) input + repeat - 1 > Integer.MAX_VALUE
                    || (long) output + (long) repeat * increment - 1 > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid SMAP range");
        }
        public Optional<Integer> sourceLine(int generated) {
            long delta = (long) generated - output;
            return delta >= 0 && delta < (long) repeat * increment
                    ? Optional.of(input + (int) (delta / increment)) : Optional.empty();
        }
    }
    public record Coroutine(String className, String method, String file, int version,
            List<Integer> suspensionLines, List<Integer> nextLines) {
        public Coroutine { suspensionLines = List.copyOf(suspensionLines); nextLines = List.copyOf(nextLines); }
        public boolean supported() { return version == 1 || version == 2; }
    }
    public record Info(String className, List<Mapping> mappings, boolean malformedSmap, Coroutine coroutine) {
        public Info { mappings = List.copyOf(mappings); }
    }

    public static List<Info> scan(ClassFileSnapshot snapshot) {
        Map<String, Long> owners = snapshot.entries().stream().collect(java.util.stream.Collectors.groupingBy(
                entry -> new ClassReader(entry.bytecode()).getClassName().replace('/', '.'), java.util.stream.Collectors.counting()));
        return snapshot.entries().stream().map(entry -> read(entry.bytecode())).flatMap(Optional::stream)
                .filter(info -> owners.get(info.className()) == 1).toList();
    }

    public static Optional<Info> read(byte[] bytecode) {
        class Visitor extends ClassVisitor {
            String owner, smap; Map<String, Object> annotation;
            Visitor() { super(Opcodes.ASM9); }
            @Override public void visit(int version, int access, String name, String signature, String superclass, String[] interfaces) { owner = name.replace('/', '.'); }
            @Override public void visitSource(String source, String debug) { smap = debug; }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals("Lkotlin/coroutines/jvm/internal/DebugMetadata;")) return null;
                annotation = new LinkedHashMap<>();
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String name, Object value) { annotation.put(name, value); }
                };
            }
        }
        var visitor = new Visitor();
        new ClassReader(bytecode).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (visitor.smap == null && visitor.annotation == null) return Optional.empty();
        List<Mapping> mappings = List.of(); boolean malformed = false;
        if (visitor.smap != null) try { mappings = parseSmap(visitor.smap); }
        catch (IllegalArgumentException invalid) { malformed = true; }
        Coroutine coroutine = null;
        if (visitor.annotation != null) {
            var a = visitor.annotation;
            if (a.get("c") instanceof String cls && a.get("m") instanceof String method && a.get("f") instanceof String file
                    && !cls.isBlank() && !method.isBlank() && !file.isBlank()) {
                int version = a.get("v") instanceof Integer value ? value : a.containsKey("nl") ? 2 : 1;
                coroutine = new Coroutine(cls, method, file, version, lines(a.get("l")), lines(a.get("nl")));
            }
        }
        return Optional.of(new Info(visitor.owner, mappings, malformed, coroutine));
    }

    private static List<Integer> lines(Object value) {
        if (!(value instanceof int[] array) || array.length > 10000) return List.of();
        return java.util.Arrays.stream(array).boxed().toList();
    }

    public static List<Mapping> parseSmap(String text) {
        if (text.length() > 2_000_000) throw new IllegalArgumentException("SMAP too large");
        String[] lines = text.replace("\r\n", "\n").split("\n");
        if (lines.length < 4 || !lines[0].equals("SMAP")) throw new IllegalArgumentException("Invalid SMAP header");
        String stratum = null, section = null; int currentFile = 0;
        Map<Integer, String[]> files = new LinkedHashMap<>(); List<Mapping> result = new ArrayList<>();
        boolean ended = false;
        var pattern = java.util.regex.Pattern.compile("(\\d+)(?:#(\\d+))?(?:,(\\d+))?:(\\d+)(?:,(\\d+))?");
        for (int i = 3; i < lines.length; i++) {
            String line = lines[i];
            if (line.equals("*E")) { ended = true; break; }
            if (line.startsWith("*S ")) { stratum = line.substring(3); files.clear(); currentFile = 0; section = null; continue; }
            if (line.startsWith("*")) { section = line; continue; }
            if (!("Kotlin".equals(stratum) || "KotlinDebug".equals(stratum))) continue;
            try {
                if ("*F".equals(section)) {
                    boolean extended = line.startsWith("+ ");
                    String[] parts = (extended ? line.substring(2) : line).split(" ", 2);
                    if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException("Invalid file");
                    int id = Integer.parseInt(parts[0]);
                    String path = extended && ++i < lines.length ? lines[i] : parts[1];
                    if (id <= 0 || path.startsWith("*") || files.putIfAbsent(id, new String[]{parts[1], path}) != null)
                        throw new IllegalArgumentException("Invalid file id");
                } else if ("*L".equals(section)) {
                    var match = pattern.matcher(line);
                    if (!match.matches()) throw new IllegalArgumentException("Invalid line mapping");
                    if (match.group(2) != null) currentFile = Integer.parseInt(match.group(2));
                    String[] file = files.get(currentFile);
                    if (file == null) throw new IllegalArgumentException("Unknown file");
                    result.add(new Mapping(stratum, file[0], file[1], Integer.parseInt(match.group(1)),
                            match.group(3) == null ? 1 : Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)),
                            match.group(5) == null ? 1 : Integer.parseInt(match.group(5))));
                    if (result.size() > 10000) throw new IllegalArgumentException("Too many mappings");
                }
            } catch (IndexOutOfBoundsException invalid) { throw new IllegalArgumentException("Truncated SMAP", invalid); }
        }
        if (!ended) throw new IllegalArgumentException("Missing SMAP terminator");
        return List.copyOf(result);
    }
}
