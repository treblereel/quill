package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Content evidence for source coordinates; timestamps cannot establish source identity. */
public final class SourceFileHashes {
    private SourceFileHashes() {}
    public static Map<String, String> captureJava(Path project, List<Path> roots) {
        Path root = project.toAbsolutePath().normalize();
        Map<String, String> hashes = new TreeMap<>();
        try {
            Path realRoot = root.toRealPath();
            for (Path sourceRoot : roots) {
                if (!Files.isDirectory(sourceRoot)) continue;
                try (var files = Files.walk(sourceRoot)) {
                    for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                        Path normalized = file.toAbsolutePath().normalize();
                        if (normalized.startsWith(root) && normalized.toRealPath().startsWith(realRoot))
                            hashes.put(root.relativize(normalized).toString().replace('\\', '/'), hash(normalized));
                    }
                }
            }
            return java.util.Collections.unmodifiableMap(hashes);
        } catch (IOException unavailable) { throw new IllegalStateException("Could not capture Java source evidence", unavailable); }
    }
    public static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file);
                 var sink = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) {
                input.transferTo(sink);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
