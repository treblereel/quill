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

    private static String fingerprint(List<Path> directories, List<Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> orderedDirectories = directories.stream()
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path directory : orderedDirectories) {
                update(digest, "classesDir", directory.toString());
                entries.stream()
                        .filter(entry -> entry.classesDirectory().equals(directory))
                        .sorted(Comparator.comparing(entry -> entry.path().toString()))
                        .forEach(entry -> {
                            update(digest, "file", directory.relativize(entry.path()).toString());
                            digest.update(entry.bytecode());
                            digest.update((byte) 0);
                        });
            }
            return HexFormat.of().formatHex(digest.digest());
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
