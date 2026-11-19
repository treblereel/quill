package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

/** Immutable bytecode captured once for all application-indexing passes. */
public final class ClassFileSnapshot {

    record Entry(Path classesDirectory, Path path, byte[] bytecode) {}

    private final List<Path> classesDirectories;
    private final List<Entry> entries;
    private final String fingerprint;

    private ClassFileSnapshot(List<Path> classesDirectories, List<Entry> entries) {
        this.classesDirectories = List.copyOf(classesDirectories);
        this.entries = List.copyOf(entries);
        this.fingerprint = fingerprint(this.classesDirectories, this.entries);
    }

    public static ClassFileSnapshot capture(List<Path> classesDirectories) {
        List<Path> normalizedDirectories = classesDirectories.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        List<Entry> entries = new ArrayList<>();
        for (Path directory : normalizedDirectories) {
            try (Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".class"))
                        .toList()) {
                    entries.add(new Entry(directory, path, Files.readAllBytes(path)));
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to capture bytecode from " + directory, e);
            }
        }
        return new ClassFileSnapshot(normalizedDirectories, entries);
    }

    public String fingerprint() {
        return fingerprint;
    }

    List<Entry> entries() {
        return entries;
    }

    /**
     * {@link #fingerprint()} of the outputs as they are now, without keeping their bytecode. Hashes are
     * reused for class files whose identity, size and timestamps are unchanged, and the rest are read
     * in parallel, so checking outputs after a build reads each new class once.
     */
    public static String fingerprint(List<Path> classesDirectories) {
        List<Path> directories = classesDirectories.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        Map<Path, List<Path>> files = new HashMap<>();
        List<Path[]> all = new ArrayList<>();
        for (Path directory : directories) {
            List<Path> classes = new ArrayList<>();
            try {
                // The walk's own attributes avoid a second stat per class file.
                Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
                    @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                        if (file.toString().endsWith(".class")
                                && (attributes.isRegularFile() || attributes.isSymbolicLink() && Files.isRegularFile(file))) classes.add(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
                files.put(directory, classes);
                classes.forEach(file -> all.add(new Path[] {directory, file}));
            } catch (IOException e) {
                throw new RuntimeException("Failed to capture bytecode from " + directory, e);
            }
        }
        Map<Path, SourceContentCache.Volume> volumes = new HashMap<>();
        directories.forEach(directory -> volumes.put(directory, SourceContentCache.volume(directory)));
        Map<Path, String> hashes = new java.util.concurrent.ConcurrentHashMap<>();
        all.parallelStream().forEach(pair -> {
            try {
                hashes.put(pair[1], SourceContentCache.hash(pair[1], volumes.get(pair[0]),
                        file -> contentHash(Files.readAllBytes(file)), SourceContentCache.CLASSES));
            } catch (IOException e) {
                throw new RuntimeException("Failed to capture bytecode from " + pair[1], e);
            }
        });
        return fingerprint(directories, files, hashes::get);
    }

    private static String fingerprint(List<Path> directories, List<Entry> entries) {
        Map<Path, List<Path>> files = new HashMap<>();
        Map<Path, byte[]> bytecode = new HashMap<>();
        for (Entry entry : entries) {
            files.computeIfAbsent(entry.classesDirectory(), ignored -> new ArrayList<>()).add(entry.path());
            bytecode.put(entry.path(), entry.bytecode());
        }
        return fingerprint(directories, files, path -> contentHash(bytecode.get(path)));
    }

    /** One digest over each output directory and the relative path and content hash of every class in it. */
    private static String fingerprint(List<Path> directories, Map<Path, List<Path>> files,
            java.util.function.Function<Path, String> contentHash) {
        MessageDigest digest = sha256();
        List<Path> orderedDirectories = directories.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        for (Path directory : orderedDirectories) {
            update(digest, "classesDir", directory.toString());
            files.getOrDefault(directory, List.of()).stream().sorted(Comparator.comparing(Path::toString)).forEach(path -> {
                update(digest, "file", directory.relativize(path).toString());
                update(digest, "sha256", contentHash.apply(path));
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String contentHash(byte[] bytecode) {
        return HexFormat.of().formatHex(sha256().digest(bytecode));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void update(MessageDigest digest, String key, String value) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
